package com.twixxty.rootrecents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Runs shell commands with elevated rights. Two ways to get them: root (`su`), or Shizuku (a shell
 * with adb rights, for phones without root). Call from a background thread only.
 */
object Root {

    const val AUTO = 0
    const val ROOT = 1
    const val SHIZUKU = 2

    @Volatile
    private var mode = AUTO

    @Volatile
    private var resolved = 0 // 0 unknown, ROOT, SHIZUKU

    /** Reads the "access method" setting. Call whenever the app starts or the setting changes. */
    fun init(ctx: Context) {
        val m = Config.get(ctx).backend
        if (m != mode) resolved = 0
        mode = m
    }

    fun reset() {
        resolved = 0
    }

    fun shizukuReady(): Boolean = try {
        rikka.shizuku.Shizuku.pingBinder() &&
            rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun shizukuRunning(): Boolean = try {
        rikka.shizuku.Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun rootWorks(): Boolean = suWorks()

    private fun suWorks(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    /** ROOT, SHIZUKU, or 0 when neither is usable right now. */
    fun backend(): Int {
        val r = resolved
        if (r != 0) return r
        val found = when (mode) {
            ROOT -> if (suWorks()) ROOT else 0
            SHIZUKU -> if (shizukuReady()) SHIZUKU else 0
            else -> if (suWorks()) ROOT else if (shizukuReady()) SHIZUKU else 0
        }
        resolved = found
        return found
    }

    /** True when commands run as the adb shell: the system's screenshot files are out of reach. */
    val isShell: Boolean get() = backend() == SHIZUKU

    private fun shizukuProcess(cmd: String): Process {
        val m = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    }

    /** Starts [cmd] with the chosen method. Stderr is separate unless [mergeErr]. */
    fun spawn(cmd: String, mergeErr: Boolean = false): Process {
        return when (backend()) {
            SHIZUKU -> shizukuProcess(if (mergeErr) "($cmd) 2>&1" else cmd)
            ROOT -> ProcessBuilder("su", "-c", cmd).redirectErrorStream(mergeErr).start()
            else -> throw IllegalStateException("no root or Shizuku")
        }
    }

    /** Runs [cmd] and returns stdout ("" on any failure). */
    fun exec(cmd: String): String = try {
        val p = spawn(cmd)
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Throwable) {
        ""
    }

    /** Like [exec] but stdout and stderr together, for commands whose errors we want to read. */
    fun execAll(cmd: String): String = try {
        val p = spawn(cmd, true)
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Throwable) {
        ""
    }

    /** Runs [cmd] and returns raw stdout bytes, or null if empty / failed. */
    fun execBytes(cmd: String): ByteArray? = try {
        val p = spawn(cmd)
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        if (bytes.isEmpty()) null else bytes
    } catch (e: Throwable) {
        null
    }

    fun available(): Boolean = backend() != 0

    /**
     * Full-resolution screenshot. Reads the raw pixels (no slow PNG encode on the phone), which is
     * several times faster; falls back to PNG if the raw layout is not what we expect.
     */
    fun screenshotFull(): Bitmap? {
        val raw = execBytes("screencap")
        if (raw != null && raw.size > 32) {
            try {
                val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val w = bb.getInt(0)
                val h = bb.getInt(4)
                val f = bb.getInt(8)
                val pix = w.toLong() * h * 4
                val header = raw.size - pix
                if (w in 1..10000 && h in 1..10000 && f == 1 && header in 12..16) {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(raw, header.toInt(), pix.toInt()))
                    return bmp
                }
            } catch (e: Throwable) {
                // use PNG
            }
        }
        val bytes = execBytes("screencap -p") ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** A fresh screenshot of whatever is on screen, scaled down. */
    fun screenshot(): Bitmap? {
        val bytes = execBytes("screencap -p") ?: return null
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}

/** A small long-lived helper process (see TaskRemover.daemon) so task commands are instant. */
object Helper {
    private var proc: Process? = null
    private var writer: java.io.Writer? = null
    private val lines = java.util.concurrent.LinkedBlockingQueue<String>()

    @Volatile
    private var ready = false
    private var starting = false

    private fun alive(): Boolean = try {
        val p = proc
        p != null && try { p.exitValue(); false } catch (e: IllegalThreadStateException) { true }
    } catch (e: Throwable) {
        false
    }

    /** Starts the helper in the background if it is not already running. */
    @Synchronized
    fun start(ctx: Context) {
        if (starting || (ready && alive())) return
        starting = true
        ready = false
        val apk = ctx.applicationInfo.sourceDir
        Thread {
            try {
                lines.clear()
                val p = Root.spawn("CLASSPATH='$apk' exec app_process /system/bin com.twixxty.rootrecents.TaskRemover daemon")
                proc = p
                writer = p.outputStream.bufferedWriter()
                Thread {
                    try {
                        val r = p.inputStream.bufferedReader()
                        while (true) {
                            val l = r.readLine() ?: break
                            lines.put(l)
                        }
                    } catch (e: Throwable) {
                        // process gone
                    }
                }.also { it.isDaemon = true }.start()
                val first = lines.poll(15, java.util.concurrent.TimeUnit.SECONDS)
                ready = first == "ready"
            } catch (e: Throwable) {
                ready = false
            } finally {
                starting = false
            }
        }.also { it.isDaemon = true }.start()
    }

    /** Sends one command and collects reply lines up to (and including) the one [done] accepts. */
    @Synchronized
    private fun call(cmd: String, waitReadyMs: Long, replyMs: Long, done: (String) -> Boolean): List<String>? {
        val t0 = System.currentTimeMillis()
        while (!(ready && alive())) {
            if (!starting && !(ready && alive())) return null // never came up: use the slow way
            if (System.currentTimeMillis() - t0 > waitReadyMs) return null
            Thread.sleep(20)
        }
        return try {
            lines.clear()
            writer?.write(cmd + "\n")
            writer?.flush()
            val out = ArrayList<String>()
            while (true) {
                val l = lines.poll(replyMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return null
                out.add(l)
                if (done(l)) break
            }
            out
        } catch (e: Throwable) {
            ready = false
            null
        }
    }

    /** true / false = the system's answer; null = the helper is not available (use the slow way). */
    fun front(id: Int): Boolean? {
        val r = call("front $id", 1200, 3000) { it.startsWith("front:") } ?: return null
        return r.last() == "front:true"
    }

    fun remove(ids: List<Int>): Set<Int>? {
        val r = call("remove ${ids.joinToString(" ")}", 1200, 4000) { it == "end" } ?: return null
        return r.mapNotNull { l ->
            val p = l.split(":")
            if (p.size == 2 && p[1] == "true") p[0].toIntOrNull() else null
        }.toSet()
    }
}

/** Things we do to other apps' tasks. All of these block, so call them off the main thread. */
object Actions {

    // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | FLAG_ACTIVITY_NO_ANIMATION.
    // NO_ANIMATION is what stops Android playing its own "slide in from the right" transition:
    // our expanding card already is the transition.
    private const val LAUNCH_FLAGS = "0x10210000"

    /**
     * Removes tasks from recents the way the stock recents screen does (the app is closed, its
     * process is left to the system). Returns the ids the system reported as removed.
     */
    fun removeTasks(ctx: Context, ids: List<Int>): Set<Int> {
        if (ids.isEmpty()) return emptySet()
        Helper.remove(ids)?.let { return it }
        val apk = ctx.applicationInfo.sourceDir
        val out = Root.exec(
            "CLASSPATH='$apk' app_process /system/bin " +
                "com.twixxty.rootrecents.TaskRemover ${ids.joinToString(" ")}"
        )
        return out.lines()
            .mapNotNull { line ->
                val parts = line.trim().split(":")
                if (parts.size == 2 && parts[1] == "true") parts[0].toIntOrNull() else null
            }
            .toSet()
    }

    fun forceStop(tasks: List<RecentTask>, neverStop: Set<String>) {
        for (t in tasks) {
            if (t.pkg in neverStop) continue
            Root.exec("/system/bin/am force-stop --user ${t.userId} ${t.pkg}")
        }
    }

    /**
     * Brings the task's app forward in the right user (work profile apps stay in the profile).
     * The window / transition animation scales are switched off just for the launch (and put back
     * in the same shell command), so the app pops in instead of sliding or zooming in. Returns
     * when the app has started (-W).
     */
    /** Starts [task] in the other half of split screen (NEW_TASK | LAUNCH_ADJACENT). */
    fun launchAdjacent(user: Int, component: String) {
        Root.exec(
            "/system/bin/am start --user $user -n '$component' " +
                "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10001000"
        )
    }

    /** Fallback when the accessibility service is not running: the split-screen key combo. */
    fun splitKey() {
        Root.exec("input keyevent 171") // KEYCODE_WINDOW (best effort)
    }

    @Volatile
    private var scaleA: String? = null

    @Volatile
    private var scaleT: String? = null

    /** Remembers the user's animation scales ahead of time, so a launch does not have to read them. */
    fun primeScales() {
        if (scaleA != null) return
        val out = Root.exec(
            "settings get global window_animation_scale; settings get global transition_animation_scale"
        ).lines().map { it.trim() }
        fun ok(v: String?) = v?.toFloatOrNull()?.takeIf { it > 0f }?.let { v } ?: "1.0"
        scaleA = ok(out.getOrNull(0))
        scaleT = ok(out.getOrNull(1))
    }

    fun launch(ctx: Context, task: RecentTask) {
        primeScales()
        val a = scaleA ?: "1.0"
        val t = scaleT ?: "1.0"
        // The scales go to 0 so no system transition shows; the helper process brings the task
        // forward instantly (the app is exactly where it was, e.g. Settings stays on its page).
        // Only if that is not possible is the app started fresh.
        Root.exec("settings put global window_animation_scale 0; settings put global transition_animation_scale 0")
        val front = if (task.empty) false else Helper.front(task.id)
        val targetComp = task.launchComponent ?: task.component
        val cmd = (if (front == true) "" else
            "/system/bin/am start -W --user ${task.userId} -n '$targetComp' " +
                "-a android.intent.action.MAIN -f $LAUNCH_FLAGS; ") +
            "sleep 0.25; " +
            "( sleep 0.5; settings put global window_animation_scale $a; " +
            "settings put global transition_animation_scale $t ) >/dev/null 2>&1 &"
        val out = Root.execAll(cmd)
        if (out.contains("Error", ignoreCase = true) || out.contains("Exception") || out.contains("unable to resolve", ignoreCase = true)) {
            val fallback = "/system/bin/am start --user ${task.userId} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${task.pkg} -f $LAUNCH_FLAGS"
            val fallbackOut = Root.execAll(fallback)
            if (fallbackOut.contains("Error", ignoreCase = true) || fallbackOut.contains("Exception")) {
                val msg = out.lines().firstOrNull { it.isNotBlank() && !it.startsWith("Starting") } ?: out
                val app = ctx.applicationContext
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, "Couldn't open (user ${task.userId}): ${msg.take(120)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun openAppInfo(task: RecentTask) {
        Root.exec(
            "/system/bin/am start --user ${task.userId} -a android.settings.APPLICATION_DETAILS_SETTINGS " +
                "-d package:${task.pkg} -f 0x10010000"
        )
    }

    /** The overlay window used for the opening animation needs "draw over other apps". */
    fun ensureOverlayPermission(ctx: Context) {
        if (android.provider.Settings.canDrawOverlays(ctx)) return
        Root.exec("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow")
    }

    /** Switches the bottom-trigger accessibility service on or off (no dialog, through root). */
    fun setTriggerService(on: Boolean) {
        val comp = TriggerAccessibilityService.COMPONENT
        val cmd = if (on) {
            "cur=\$(settings get secure enabled_accessibility_services); " +
                "case \":\$cur:\" in *:$comp:*) ;; " +
                "*) if [ \"\$cur\" = null ] || [ -z \"\$cur\" ]; then new=$comp; else new=\"\$cur:$comp\"; fi; " +
                "settings put secure enabled_accessibility_services \"\$new\";; esac; " +
                "settings put secure accessibility_enabled 1"
        } else {
            "cur=\$(settings get secure enabled_accessibility_services); " +
                "new=\$(echo \"\$cur\" | sed \"s#$comp##; s#::#:#g; s#^:##; s#:\\\$##\"); " +
                "if [ -z \"\$new\" ]; then settings delete secure enabled_accessibility_services; " +
                "else settings put secure enabled_accessibility_services \"\$new\"; fi"
        }
        Root.exec(cmd)
    }

    /** Takes a screenshot the way the screenshot key does (KEYCODE_SYSRQ). */
    fun screenshotKey() {
        Root.exec("input keyevent 120")
    }

    /** Starts the recents screen from a place that may not be allowed to start activities. */
    fun startRecents(extras: String = "") {
        Root.exec("/system/bin/am start -n com.twixxty.rootrecents/.RecentsActivity -a com.twixxty.rootrecents.SHOW $extras -f 0x10010000")
    }
}
