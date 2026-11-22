package com.twixxty.rootrecents

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads the touchscreen straight from the kernel with root (`getevent`), so the bottom trigger
 * works even where the system's own gesture handle swallows touches before any overlay window
 * can see them (ColorOS / OxygenOS). It only watches: the system still sees the touches too.
 *
 * Only touches that START inside the bottom [zonePx] of the screen are reported.
 */
class RawTouch(
    private val screenW: Int,
    private val screenH: Int,
    /** Surface.ROTATION_0 / 90 / 180 / 270: the panel itself never rotates, the screen does. */
    private val rotation: Int,
    private val zonePx: Int,
    private val onPointer: (action: Int, x: Float, y: Float) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var process: Process? = null

    fun start() {
        if (running) return
        running = true
        Thread { loop() }.also { it.isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try {
            process?.destroy()
        } catch (e: Exception) {
            // ignore
        }
        process = null
    }

    private fun post(a: Int, x: Float, y: Float) = main.post { onPointer(a, x, y) }

    private fun loop() {
        val info = Root.exec("getevent -lp")
        var dev: String? = null
        var maxX = 0
        var maxY = 0
        var cur: String? = null
        var cx = 0
        var cy = 0
        for (line in info.lines()) {
            val t = line.trim()
            if (t.startsWith("add device")) {
                cur = t.substringAfter(": ").trim()
                cx = 0; cy = 0
            } else if (t.contains("ABS_MT_POSITION_X")) {
                cx = maxOf(cx, Regex("""max\s+(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
            } else if (t.contains("ABS_MT_POSITION_Y")) {
                cy = maxOf(cy, Regex("""max\s+(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
            }
            if (dev == null && cur != null && cx > 0 && cy > 0) {
                dev = cur; maxX = cx; maxY = cy
            }
        }
        if (dev == null || !running) {
            running = false
            return
        }

        try {
            val p = Root.spawn("getevent -l $dev", true)
            process = p
            val r = BufferedReader(InputStreamReader(p.inputStream))

            var slot = 0
            var rx = -1
            var ry = -1
            var down = false
            var tracking = false // our gesture is in progress
            var ignore = false // touch started outside the zone
            var moved = false
            var wentDown = false
            var wentUp = false

            while (running) {
                val line = r.readLine() ?: break
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 3) continue
                val type = parts[parts.size - 3]
                val code = parts[parts.size - 2]
                val value = parts[parts.size - 1]
                when {
                    type == "EV_ABS" && code == "ABS_MT_SLOT" -> slot = value.toLongOrNull(16)?.toInt() ?: 0
                    slot != 0 -> {}
                    type == "EV_ABS" && code == "ABS_MT_TRACKING_ID" -> {
                        if (value.equals("ffffffff", true)) wentUp = true else wentDown = true
                    }
                    type == "EV_KEY" && code == "BTN_TOUCH" -> {
                        if (value == "DOWN") wentDown = true else if (value == "UP") wentUp = true
                    }
                    type == "EV_ABS" && code == "ABS_MT_POSITION_X" -> {
                        rx = value.toLong(16).toInt(); moved = true
                    }
                    type == "EV_ABS" && code == "ABS_MT_POSITION_Y" -> {
                        ry = value.toLong(16).toInt(); moved = true
                    }
                    type == "EV_SYN" && code == "SYN_REPORT" -> {
                        val fx = if (rx >= 0) rx.toFloat() / maxX else 0f
                        val fy = if (ry >= 0) ry.toFloat() / maxY else 0f
                        val x: Float
                        val y: Float
                        when (rotation) {
                            1 -> { x = fy * screenW; y = (1f - fx) * screenH }
                            2 -> { x = (1f - fx) * screenW; y = (1f - fy) * screenH }
                            3 -> { x = (1f - fy) * screenW; y = fx * screenH }
                            else -> { x = fx * screenW; y = fy * screenH }
                        }
                        if (wentDown && !down) {
                            down = true
                            ignore = y < screenH - zonePx
                            if (!ignore) {
                                tracking = true
                                post(MotionEvent.ACTION_DOWN, x, y)
                            }
                        } else if (down && moved && tracking) {
                            post(MotionEvent.ACTION_MOVE, x, y)
                        }
                        if (wentUp && down) {
                            if (tracking) post(MotionEvent.ACTION_UP, x, y)
                            down = false
                            tracking = false
                            ignore = false
                            rx = -1; ry = -1
                        }
                        moved = false
                        wentDown = false
                        wentUp = false
                    }
                }
            }
        } catch (e: Exception) {
            // stream ended
        } finally {
            running = false
        }
    }
}
