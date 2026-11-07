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
        if (state == S.SWIPED) {
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
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                state = S.TRACK
                gestureActive = true
                sx = e.rawX; sy = e.rawY
                // start loading the task list now, so it is ready when the switcher opens
                if (!RecentsActivity.visible) Prefetch.begin(this)
            }

            MotionEvent.ACTION_MOVE -> {
                when (state) {
                    S.TRACK -> if (sy - e.rawY >= lengthPx) {
                        state = S.SWIPED
                        ax = e.rawX; ay = e.rawY
                        handler.postDelayed(holdRunnable, holdMs)
                    }

                    S.SWIPED -> if (hypot(e.rawX - ax, e.rawY - ay) > slopPx * 2f) {
                        // still moving: restart the hold timer from here
                        ax = e.rawX; ay = e.rawY
                        handler.removeCallbacks(holdRunnable)
                        handler.postDelayed(holdRunnable, holdMs)
                    }

                    S.PEEK -> PeekBridge.move(e.rawX, e.rawY)
                    else -> {}
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
                // Swiped up and let go without holding: go home.
                if (state == S.SWIPED && e.actionMasked == MotionEvent.ACTION_UP) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
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
        return true
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
