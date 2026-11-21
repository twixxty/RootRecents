package com.twixxty.rootrecents

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
    private val numActRe = Regex("""numActivities=(\d+)""")
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

    /** The tasks without thumbnails yet, plus where each task's snapshot file is. */
    class Listing(
        val tasks: List<RecentTask>,
        val allIds: Set<Int>,
        val paths: Map<Int, String>,
        val fgId: Int,
        val noRoot: Boolean = false,
        /** The app in front: user id and "package/class" (null if none). */
        val fgUser: Int = 0,
        val fgComponent: String? = null
    )

    /** Phase 1, fast: one root call for the task list and the snapshot file names. No image decoding. */
    fun loadList(
        ctx: Context,
        hidden: Set<String>,
        showForeground: Boolean,
        hiddenApps: Set<String> = emptySet()
    ): Listing {
        val raw = Root.exec(
            "dumpsys activity recents; echo '@@SNAPS'; ls -d /data/system_ce/*/snapshots/*.jpg 2>/dev/null"
        )
        val k = raw.indexOf("@@SNAPS")
        val dump = if (k >= 0) raw.substring(0, k) else raw
        if (dump.isBlank()) return Listing(emptyList(), emptySet(), emptyMap(), -1, true)
        val files = if (k >= 0) raw.substring(k + 7).lines() else emptyList()

        val paths = HashMap<Int, String>()
        for (f in files) {
            val line = f.trim()
            if (!line.endsWith(".jpg")) continue
            val name = line.substringAfterLast('/')
            val id = name.substringBefore('.').substringBefore('_').toIntOrNull() ?: continue
            val old = paths[id]
            if (old == null || (old.endsWith("_reduced.jpg") && !line.endsWith("_reduced.jpg"))) paths[id] = line
        }

        val all = parse(dump, ctx.packageName)
        val handles = userHandles(ctx)
        val first = all.firstOrNull()
        val fgId = if (first != null && !first.isHome && first.visible != false) first.id else -1
        val parsed = all.filter { !it.isHome }
        val wanted = parsed.filter { p ->
            !hidden.contains(p.id.toString()) && (showForeground || p.id != fgId) && p.pkg !in hiddenApps
        }

        val tasks = ArrayList<RecentTask>()
        for (p in wanted) {
            // The directory a snapshot lives in is the most reliable "which user" signal.
            val userId = paths[p.id]?.let { snapUserRe.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: p.bestGuessUser
            val res = resolve(ctx, p.pkg, userId, handles[userId]) ?: continue
            val cls = if (p.cls.startsWith(".")) p.pkg + p.cls else p.cls
            tasks.add(
                RecentTask(
                    id = p.id, userId = userId, pkg = p.pkg, component = "${p.pkg}/$cls",
                    label = res.first, icon = res.second, thumb = null, foreground = p.id == fgId
                )
            )
        }
        var fgComp: String? = null
        var fgUser = 0
        if (fgId != -1 && first != null) {
            val cls = if (first.cls.startsWith(".")) first.pkg + first.cls else first.cls
            fgComp = "${first.pkg}/$cls"
            fgUser = paths[first.id]?.let { snapUserRe.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: first.bestGuessUser
        }
        return Listing(tasks, parsed.map { it.id }.toSet(), paths, fgId, false, fgUser, fgComp)
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
            var numActs: Int? = null
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
                if (numActs == null) numActs = numActRe.find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (blockUser == null) blockUser = blockUserRe.find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (effUser == null) effUser = effUidRe.find(line)?.groupValues?.get(1)?.toIntOrNull()
                j++
            }

            // numActivities=0: an empty shell of a task (a "ghost"), nothing to go back to
            if (id != null && pkg != null && cls != null && pkg != ownPkg && numActs != 0) {
                out.add(Parsed(id, headerUser, blockUser, effUser, pkg, cls, visible, type))
            }
            i = j
        }
        return out
    }

    /** Phase 2: reads the given snapshot files with one root shell, decoding the JPEGs in parallel. */
    fun loadThumbs(paths: Map<Int, String>): Map<Int, Bitmap> {
        if (paths.isEmpty()) return emptyMap()
        val cmd = paths.entries.joinToString("; ") { (id, p) ->
            "if [ -f '$p' ]; then echo \"@@$id \$(stat -c %s '$p') $p\"; cat '$p'; echo; fi"
        }
        val bytes = Root.execBytes(cmd) ?: return emptyMap()

        class Raw(val id: Int, val start: Int, val size: Int)
        val raws = ArrayList<Raw>()
        var i = 0
        val at = '@'.code.toByte()
        while (i < bytes.size - 2) {
            if (bytes[i] == at && bytes[i + 1] == at) {
                var nl = i
                while (nl < bytes.size && bytes[nl] != '\n'.code.toByte()) nl++
                if (nl >= bytes.size) break
                val parts = String(bytes, i + 2, nl - i - 2).split(" ", limit = 3)
                val id = parts.getOrNull(0)?.toIntOrNull()
                val size = parts.getOrNull(1)?.toIntOrNull()
                if (id == null || size == null || nl + 1 + size > bytes.size) break
                raws.add(Raw(id, nl + 1, size))
                i = nl + 1 + size
            } else i++
        }

        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        val futures = raws.map { r ->
            pool.submit(Callable<Pair<Int, Bitmap?>> {
                val o = BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inPreferredConfig = Bitmap.Config.RGB_565 // half the memory
                }
                Pair(r.id, BitmapFactory.decodeByteArray(bytes, r.start, r.size, o))
            })
        }
        val out = HashMap<Int, Bitmap>()
        for (f in futures) {
            try {
                val (id, bmp) = f.get()
                if (bmp != null) out[id] = bmp
            } catch (e: Exception) {
                // that one just has no thumbnail
            }
        }
        pool.shutdown()
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
