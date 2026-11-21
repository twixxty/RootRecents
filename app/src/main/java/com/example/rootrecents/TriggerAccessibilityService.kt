package com.example.rootrecents

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

/**
 * The bottom trigger: a thin touch strip flush with the bottom edge of the screen.
 *
 *  - swipe up (the configured length) and let go: home screen
 *  - swipe up and hold still (the configured time): recents; with the peek on, the screen blurs, the
 *    cards peek in from the left and follow your finger until you let go (this service keeps the
 *    touch stream and hands it to the recents screen through [PeekBridge])
 *
 * It is an accessibility service (switched on through root, no dialog) because its overlay window
 * is not hidden by apps like system Settings, which hide ordinary overlay windows.
 */
class TriggerAccessibilityService : AccessibilityService() {

    companion object {
        const val COMPONENT = "com.example.rootrecents/com.example.rootrecents.TriggerAccessibilityService"

        @Volatile
        var instance: TriggerAccessibilityService? = null
            private set
    }

    private enum class S { IDLE, TRACK, SWIPED, PEEK, DONE }

    private lateinit var wm: WindowManager
    private lateinit var config: Config
    private val handler = Handler(Looper.getMainLooper())
    private var raw: RawTouch? = null
    private var forwarding = false

    @Volatile
    private var shadeOpen = false
    private var shadeCheck: Thread? = null
    private var strip: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private var state = S.IDLE
    private var gestureActive = false
    private var pendingRebuild = false
    private var sx = 0f
    private var sy = 0f
    private var ax = 0f
    private var ay = 0f
    private var lengthPx = 0f
    private var holdMs = 350L
    private var peekOn = true
    private var slopPx = 0f

    private val holdRunnable = Runnable {
        if (state == S.SWIPED && shadeOpen) {
            // the panel is open: a swipe up just closes it
            state = S.DONE
            collapseShade()
        } else if (state == S.SWIPED) {
            if (peekOn) {
                state = S.PEEK
                PeekBridge.begin(ax, ay)
                openRecents(true)
            } else {
                state = S.DONE
                openRecents(false)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WindowManager::class.java)
        RecentsActivity.onVisibility = { updateTouchable() }
        rebuild()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        removeStrip()
        RecentsActivity.onVisibility = null
        instance = null
        return super.onUnbind(intent)
    }

    /** Re-reads the settings and rebuilds the strip (call after settings changed). */
    fun rebuild() {
        config = Config.get(this)
        if (gestureActive) {
            // Rebuilding now would remove the strip while a finger is on it.
            pendingRebuild = true
            return
        }
        buildStrip()
    }

    private fun buildStrip() {
        removeStrip()
        if (!config.triggerEnabled) return
        val d = resources.displayMetrics.density
        lengthPx = config.int(Config.TRIG_LENGTH) * d
        holdMs = config.int(Config.TRIG_HOLD).toLong()
        peekOn = config.triggerHold
        slopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        val v = View(this)
        v.setOnTouchListener { _, e -> onTouch(e) }
        // Flush with the very bottom edge of the display (over the gesture bar), not above it.
        val zone = (config.int(Config.TRIG_ZONE) * d).toInt()
        val sw: Int
        val sh: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            sw = b.width(); sh = b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            sw = m.widthPixels; sh = m.heightPixels
        }
        if (config.rawTouch) {
                        val zonePx = (config.int(Config.TRIG_ZONE) * d).toInt()
            raw = RawTouch(sw, sh, zonePx) { a, px, py -> onPointer(a, px, py) }
                .also { it.start() }
            return
        }

        val params = WindowManager.LayoutParams(
            sw, zone,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = sh - zone
        if (Build.VERSION.SDK_INT >= 30) params.fitInsetsTypes = 0
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            wm.addView(v, params)
            strip = v
            lp = params
            updateTouchable()
        } catch (e: Exception) {
            // no strip then
        }
    }

    private fun removeStrip() {
        raw?.stop()
        raw = null
        // never leave the recents screen waiting for a release that can no longer come
        if (state == S.PEEK) PeekBridge.release()
        state = S.IDLE
        gestureActive = false
        strip?.let {
            try {
                wm.removeViewImmediate(it)
            } catch (e: Exception) {
                // already gone
            }
        }
        strip = null
        lp = null
    }

    /** While recents is on screen the strip lets touches through (recents has its own pull-up). */
    private fun updateTouchable() {
        if (gestureActive) return // changing flags mid-gesture would cancel it
        val v = strip ?: return
        val p = lp ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val want = if (RecentsActivity.visible) p.flags or flag else p.flags and flag.inv()
        if (want != p.flags) {
            p.flags = want
            try {
                wm.updateViewLayout(v, p)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun onTouch(e: MotionEvent): Boolean {
        onPointer(e.actionMasked, e.rawX, e.rawY)
        return true
    }

    /** Is the notification shade / quick settings pulled down? (best effort, through root) */
    private fun checkShade() {
        shadeOpen = false
        shadeCheck = Thread {
            val out = Root.exec(
                "dumpsys window | grep -E 'mCurrentFocus|mFocusedWindow'; dumpsys statusbar | grep -i -E 'expanded'"
            )
            val focusShade = out.lines().any {
                (it.contains("mCurrentFocus") || it.contains("mFocusedWindow")) &&
                    (it.contains("NotificationShade") || it.contains("StatusBar"))
            }
            val flag = Regex("(?i)(expandedvisible|panelexpanded|shadeexpanded|isexpanded)\\s*[=:]\\s*true")
                .containsMatchIn(out)
            shadeOpen = focusShade || flag
        }.also { it.start() }
    }

    private fun collapseShade() {
        Thread { Root.exec("cmd statusbar collapse") }.start()
    }

    /** Swipe-up released: close the shade if it is open, otherwise go home. */
    private fun homeOrCollapse() {
        Thread {
            try {
                shadeCheck?.join(700)
            } catch (e: InterruptedException) {
                // ignore
            }
            if (shadeOpen) collapseShade() else handler.post { performGlobalAction(GLOBAL_ACTION_HOME) }
        }.start()
    }

    private fun onPointer(action: Int, x: Float, y: Float) {
        // Switcher is open and a root-read touch started on the gesture handle: it drives the pull-up.
        if (raw != null && RecentsActivity.visible && state == S.IDLE &&
            (forwarding || action == MotionEvent.ACTION_DOWN)
        ) {
            forwarding = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
            RecentsActivity.rawPull?.invoke(action, x, y)
            return
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                state = S.TRACK
                gestureActive = true
                sx = x; sy = y
                // start loading the task list now, so it is ready when the switcher opens
                if (!RecentsActivity.visible) {
                    Prefetch.begin(this)
                    checkShade()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (state) {
                    S.TRACK -> if (sy - y >= lengthPx) {
                        state = S.SWIPED
                        ax = x; ay = y
                        handler.postDelayed(holdRunnable, holdMs)
                    }

                    S.SWIPED -> if (hypot(x - ax, y - ay) > slopPx * 2f) {
                        // still moving: restart the hold timer from here
                        ax = x; ay = y
                        handler.removeCallbacks(holdRunnable)
                        handler.postDelayed(holdRunnable, holdMs)
                    }

                    S.PEEK -> PeekBridge.move(x, y)
                    else -> {}
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
                // Swiped up and let go without holding: go home.
                if (state == S.SWIPED && action == MotionEvent.ACTION_UP) {
                    homeOrCollapse()
                }
                if (state == S.PEEK) PeekBridge.release()
                state = S.IDLE
                gestureActive = false
                if (pendingRebuild) {
                    pendingRebuild = false
                    buildStrip()
                } else {
                    updateTouchable()
                }
            }
        }
    }

    private fun openRecents(peek: Boolean) {
        if (RecentsActivity.visible) return
        val i = Intent(this, RecentsActivity::class.java)
            .setAction("com.example.rootrecents.SHOW")
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            .putExtra("peek", peek)
        try {
            startActivity(i)
        } catch (e: Exception) {
            // handled below
        }
        // Background starts can be silently blocked: if nothing showed up, start it through root.
        handler.postDelayed({
            if (!RecentsActivity.visible && (state == S.PEEK || state == S.DONE || state == S.IDLE)) {
                Thread { Actions.startRecents(if (peek) "--ez peek true" else "") }.start()
            }
        }, 350)
    }
}
