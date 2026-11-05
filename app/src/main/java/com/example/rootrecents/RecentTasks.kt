package com.example.rootrecents

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.UserManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class RecentTask(
    val id: Int,
    /** 0 = personal, 10+ = work profile / secondary users */
    val userId: Int,
    val pkg: String,
    /** "package/class", usable with `am start -n` */
    val component: String,
    val label: CharSequence,
    val icon: Drawable,
    val thumb: Bitmap?,
    /** True for the app that was on screen when the switcher opened. */
    val foreground: Boolean
)

class RecentsResult(val tasks: List<RecentTask>, val allIds: Set<Int>)

/**
 * Reads the system's recent task list with `dumpsys activity recents` and loads the
 * snapshot JPEGs that system_server keeps in /data/system_ce/<user>/snapshots.
 *
 * Output formats differ a little between Android versions, so parsing is deliberately lenient,
 * and the user a task belongs to is cross-checked against where its snapshot file lives.
 */
object RecentTasks {

    private val recentHeader = Regex("""Recent #(\d+):""")
    private val taskId = Regex("""Task\{\w+ #(\d+)""")
    private val taskType = Regex("""type=(\w+)""")
    private val userRe = Regex("""\bU=(\d+)""")
    private val blockUserRe = Regex("""\buserId=(\d+)""")
    private val effUidRe = Regex("""effectiveUid=u(\d+)a""")
    private val visibleRe = Regex("""visible=(true|false)""")
    private val realActivity = Regex("""realActivity=([\w.]+)/([\w.$]+)""")
    private val cmp = Regex("""cmp=([\w.]+)/([\w.$]+)""")
    private val snapUserRe = Regex("""/data/system_ce/(\d+)/snapshots/""")

    private const val MAX_BLOCK_LINES = 60

    private class Parsed(
        val id: Int,
        val headerUser: Int?,
        val blockUser: Int?,
        val effUser: Int?,
        val pkg: String,
        val cls: String,
        val visible: Boolean?,
        val type: String?
    ) {
        val isHome: Boolean get() = type == "home"
        val bestGuessUser: Int get() = headerUser ?: blockUser ?: effUser ?: 0
    }

    private class Snap(val user: Int?, val bitmap: Bitmap?, val path: String?)

    fun load(
        ctx: Context,
        hidden: Set<String>,
        showForeground: Boolean,
        liveShot: Bitmap?
    ): RecentsResult {
        val all = parse(Root.exec("dumpsys activity recents"), ctx.packageName)
        val handles = userHandles(ctx)

        // Our own task is excluded from recents, so the most recent entry is the app that was in
        // front when the switcher opened (unless that was the home screen).
        val first = all.firstOrNull()
        val fgId = if (first != null && !first.isHome && first.visible != false) first.id else -1
        val parsed = all.filter { !it.isHome }

        val wanted = parsed.filter { p ->
            !hidden.contains(p.id.toString()) && (showForeground || p.id != fgId)
        }

        // Each snapshot read spawns `su`, so do them in parallel.
        val pool = Executors.newFixedThreadPool(4)
        val futures = wanted.map { p -> pool.submit(Callable<Snap> { loadSnapshot(p.id) }) }

        val tasks = ArrayList<RecentTask>()
        var liveUsed = false
        wanted.forEachIndexed { i, p ->
            val snap = try {
                futures[i].get()
            } catch (e: Exception) {
                Snap(null, null, null)
            }
            // The directory a snapshot lives in is the most reliable "which user" signal.
            val userId = snap.user ?: p.bestGuessUser
            val res = resolve(ctx, p.pkg, userId, handles[userId])
            if (res != null) {
                val isFg = p.id == fgId
                var thumb = snap.bitmap
                if (isFg && liveShot != null && !liveUsed) {
                    thumb = liveShot
                    liveUsed = true
                }
                val cls = if (p.cls.startsWith(".")) p.pkg + p.cls else p.cls
                tasks.add(
                    RecentTask(
                        id = p.id,
                        userId = userId,
                        pkg = p.pkg,
                        component = "${p.pkg}/$cls",
                        label = res.first,
                        icon = res.second,
                        thumb = thumb,
                        foreground = isFg
                    )
                )
            }
        }
        pool.shutdown()
        return RecentsResult(tasks, parsed.map { it.id }.toSet())
    }

    /** Human-readable dump of what was parsed, for the Diagnostics button in settings. */
    fun debugReport(ctx: Context): String {
        val dump = Root.exec("dumpsys activity recents")
        val parsed = parse(dump, ctx.packageName).filter { !it.isHome }
        val sb = StringBuilder()
        sb.append("Parsed ${parsed.size} tasks\n\n")
        for (p in parsed.take(12)) {
            val snap = loadSnapshot(p.id)
            sb.append("#${p.id} ${p.pkg}/${p.cls}\n")
            sb.append("  header U=${p.headerUser} userId=${p.blockUser} effUid=${p.effUser} visible=${p.visible}\n")
            sb.append("  snapshot: ${snap.path ?: "none"}  (user ${snap.user})\n\n")
        }
        sb.append("---- raw dumpsys (first lines) ----\n")
        sb.append(dump.lines().filter { it.contains("Recent #") || it.contains("userId=") }.take(30).joinToString("\n"))
        return sb.toString()
    }

    /** Maps user id -> UserHandle for every profile we can see (personal + work). */
    private fun userHandles(ctx: Context): Map<Int, UserHandle> {
        val map = HashMap<Int, UserHandle>()
        try {
            val um = ctx.getSystemService(UserManager::class.java)
            // UserHandle.hashCode() is the user id.
            um?.userProfiles?.forEach { map[it.hashCode()] = it }
        } catch (e: Exception) {
            // no badges then
        }
        return map
    }

    /** Label + icon for [pkg] as installed in [userId]; work apps come back with the badge. */
    private fun resolve(
        ctx: Context,
        pkg: String,
        userId: Int,
        handle: UserHandle?
    ): Pair<CharSequence, Drawable>? {
        val pm = ctx.packageManager
        if (userId != 0 && handle != null) {
            try {
                val la = ctx.getSystemService(LauncherApps::class.java)
                val info = la?.getActivityList(pkg, handle)?.firstOrNull()
                if (info != null) return Pair(info.label, info.getBadgedIcon(0))
            } catch (e: Exception) {
                // fall through to PackageManager
            }
        }
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            var label: CharSequence = pm.getApplicationLabel(ai)
            var icon: Drawable = pm.getApplicationIcon(ai)
            if (userId != 0 && handle != null) {
                icon = pm.getUserBadgedIcon(icon, handle)
                label = pm.getUserBadgedLabel(label, handle)
            }
            Pair(label, icon)
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(dump: String, ownPkg: String): List<Parsed> {
        val lines = dump.lines()
        val out = ArrayList<Parsed>()
        var i = 0
        while (i < lines.size) {
            val header = lines[i]
            if (!recentHeader.containsMatchIn(header)) {
                i++
                continue
            }
            val id = taskId.find(header)?.groupValues?.get(1)?.toIntOrNull()
            val type = taskType.find(header)?.groupValues?.get(1)
            val headerUser = userRe.find(header)?.groupValues?.get(1)?.toIntOrNull()
            val visible = visibleRe.find(header)?.groupValues?.get(1)?.let { it == "true" }

            // Collect the block until the next "Recent #" line.
            var pkg: String? = null
            var cls: String? = null
            var blockUser: Int? = null
            var effUser: Int? = null
            var j = i + 1
            while (j < lines.size && j - i < MAX_BLOCK_LINES && !recentHeader.containsMatchIn(lines[j])) {
                val line = lines[j]
                if (pkg == null) {
                    val m = realActivity.find(line) ?: cmp.find(line)
                    if (m != null) {
                        pkg = m.groupValues[1]
                        cls = m.groupValues[2]
                    }
                }
                if (blockUser == null) blockUser = blockUserRe.find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (effUser == null) effUser = effUidRe.find(line)?.groupValues?.get(1)?.toIntOrNull()
                j++
            }

            if (id != null && pkg != null && cls != null && pkg != ownPkg) {
                out.add(Parsed(id, headerUser, blockUser, effUser, pkg, cls, visible, type))
            }
            i = j
        }
        return out
    }

    /**
     * Task ids are unique across users, so look for the snapshot in every user's folder in one
     * `su` call. Output is the path on the first line, then the JPEG bytes.
     */
    private fun loadSnapshot(taskId: Int): Snap {
        val cmd = "for f in /data/system_ce/*/snapshots/$taskId.jpg " +
            "/data/system_ce/*/snapshots/${taskId}_reduced.jpg; do " +
            "if [ -f \"\$f\" ]; then echo \"\$f\"; cat \"\$f\"; break; fi; done"
        val bytes = Root.execBytes(cmd) ?: return Snap(null, null, null)
        val nl = bytes.indexOf('\n'.code.toByte())
        if (nl <= 0 || nl >= bytes.size - 1) return Snap(null, null, null)
        val path = String(bytes, 0, nl)
        val user = snapUserRe.find(path)?.groupValues?.get(1)?.toIntOrNull()
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = BitmapFactory.decodeByteArray(bytes, nl + 1, bytes.size - nl - 1, opts)
        return Snap(user, bmp, path)
    }
}
