package com.twixxty.rootrecents

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
        const val COMPONENT = "com.twixxty.rootrecents/com.twixxty.rootrecents.TriggerAccessibilityService"

        @Volatile
        var instance: TriggerAccessibilityService? = null
            private set
    }

    private enum class S { IDLE, TRACK, SWIPED, PEEK, SIDE, DONE }

    private lateinit var wm: WindowManager
    private lateinit var config: Config
    private val handler = Handler(Looper.getMainLooper())
    private var raw: RawTouch? = null

    /** The app in front has hidden the navigation bar (full-screen): found out through root. */
    @Volatile
    private var navHidden = false
    private var ignoring = false

    private var lastRot = 0
    private var dm: android.hardware.display.DisplayManager? = null
    private val dl = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            @Suppress("DEPRECATION")
            val r = wm.defaultDisplay.rotation
            if (r != lastRot) rebuild() // also 90 -> 270, which is no configuration change
        }
    }

    private val pollRunnable = Runnable { checkShade(false) }
    private var lastArm = 0L
    private var prefetchJob: Prefetch.Job? = null

    private var waitingHome: (() -> Unit)? = null

    @Volatile
    private var lastPkg: String? = null
    private var homeSentAt = 0L
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
        } else if (state == S.SWIPED && !armed("Swipe up and hold again to open recents")) {
            state = S.DONE
        } else if (state == S.SWIPED) {
            if (peekOn) {
                state = S.PEEK
                buzz(true)
                PeekBridge.begin(ax, ay)
                openRecents(true)
            } else {
                state = S.DONE
                openRecents(false)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // rotated: the strip / touch mapping must follow the new screen size
        if (::wm.isInitialized) rebuild()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        dm?.registerDisplayListener(dl, handler)
        Root.init(applicationContext)
        Thread { Helper.start(applicationContext) }.start()
        wm = getSystemService(WindowManager::class.java)
        RecentsActivity.onVisibility = { updateTouchable() }
        rebuild()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != packageName && pkg != "com.android.systemui") {
                lastPkg = pkg
                if (config.stockLike || config.misclick) {
                    handler.removeCallbacks(pollRunnable)
                    handler.postDelayed(pollRunnable, 700) // a different app may be full-screen
                }
            }
        }
        val go = waitingHome ?: return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName == packageName) return
        // the home screen (or something new) has a window up: give it a moment to draw, then go
        waitingHome = null
        val wait = (70L - (android.os.SystemClock.uptimeMillis() - homeSentAt)).coerceAtLeast(40L)
        handler.postDelayed({ go() }, wait)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        dm?.unregisterDisplayListener(dl)
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
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        sw = m.widthPixels; sh = m.heightPixels
        @Suppress("DEPRECATION")
        lastRot = wm.defaultDisplay.rotation
        if (config.rawTouch) {
                        val zonePx = (config.int(Config.TRIG_ZONE) * d).toInt()
            raw = RawTouch(sw, sh, lastRot, zonePx) { a, px, py -> onPointer(a, px, py) }
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
        handler.removeCallbacks(pollRunnable)
        navHidden = false
        raw?.stop()
        raw = null
        // never leave the recents screen waiting for a release that can no longer come
        if (state == S.PEEK || state == S.SIDE) PeekBridge.release()
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
        val blocked = RecentsActivity.visible || (config.stockLike && navHidden)
        val want = if (blocked) p.flags or flag else p.flags and flag.inv()
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
    private fun checkShade(resetShade: Boolean = true) {
        if (resetShade) shadeOpen = false
        val t = Thread {
            val out = Root.exec(
                "dumpsys window | grep -E 'mCurrentFocus|mFocusedWindow|InsetsSource'; " +
                    "dumpsys statusbar | grep -i -E 'expanded'"
            )
            val focusShade = out.lines().any {
                (it.contains("mCurrentFocus") || it.contains("mFocusedWindow")) &&
                    (it.contains("NotificationShade") || it.contains("StatusBar"))
            }
            val flag = Regex("(?i)(expandedvisible|panelexpanded|shadeexpanded|isexpanded)\\s*[=:]\\s*true")
                .containsMatchIn(out)
            shadeOpen = focusShade || flag

            // Is the navigation bar hidden (an app in full-screen)? The window manager lists the
            // bar as an inset source with a visible=true/false flag.
            val vis = out.lines()
                .filter {
                    it.contains("InsetsSource", true) &&
                        (it.contains("NAVIGATION_BAR") || it.contains("navigationBar", true))
                }
                .mapNotNull { Regex("visible=(true|false)").find(it)?.groupValues?.get(1) }
            val hidden = vis.isNotEmpty() && vis.none { it == "true" }
            if (hidden != navHidden) {
                navHidden = hidden
                handler.post { updateTouchable() }
            }
            handler.post {
                handler.removeCallbacks(pollRunnable)
                // while hidden, keep looking so we notice when the bar is swiped back into view
                if (navHidden && (config.stockLike || config.misclick)) handler.postDelayed(pollRunnable, 1200)
            }
        }
        shadeCheck = t
        t.start()
    }

    private fun collapseShade() {
        Thread { Root.exec("cmd statusbar collapse") }.start()
    }

    /** Blur the screen, let the system's own home animation play under it, then clear the blur. */
    private fun goHomeAnimated() {
        val anim = HomeAnim(this, config)
        if (!anim.show()) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        anim.blurIn {
            var fired = false
            val go = {
                if (!fired) {
                    fired = true
                    handler.postDelayed({ anim.blurOut() }, config.int(Config.HOME_HOLD).toLong())
                }
            }
            waitingHome = go
            homeSentAt = android.os.SystemClock.uptimeMillis()
            lastPkg = null
            performGlobalAction(GLOBAL_ACTION_HOME)
            // no window event arrived (some launchers don't send one): carry on anyway
            handler.postDelayed({
                if (waitingHome === go) waitingHome = null
                go()
            }, 800)
        }
    }

    /** Swipe-up released: close the shade if it is open, otherwise go home. */
    private fun homeOrCollapse() {
        Thread {
            try {
                shadeCheck?.join(400)
            } catch (e: InterruptedException) {
                // ignore
            }
            if (shadeOpen) {
                collapseShade()
                return@Thread
            }
            if (!armed("Swipe up again to go home")) return@Thread
            var animate = false
            if (config.homeAnim) {
                // already on the home screen? then there is nothing to shrink
                val home = packageManager.resolveActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
                )?.activityInfo?.packageName
                val last = lastPkg
                val onHome = last != null && last == home
                animate = !onHome
            }
            handler.post { if (animate) goHomeAnimated() else performGlobalAction(GLOBAL_ACTION_HOME) }
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
        if (action == MotionEvent.ACTION_DOWN) ignoring = false // a new touch always starts clean
        if (ignoring) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ignoring = false
            return
        }
        if (action == MotionEvent.ACTION_DOWN) {
            val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
            if (locked || (config.stockLike && navHidden)) {
                ignoring = true // the stock gesture would not do anything here either
                // the swipe probably brings the bar back: look again shortly
                handler.postDelayed({ checkShade(false) }, 500)
                return
            }
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                state = S.TRACK
                gestureActive = true
                sx = x; sy = y
                // start loading the task list now, so it is ready when the switcher opens
                if (!RecentsActivity.visible) {
                    prefetchJob = Prefetch.begin(this, capture = true)
                    Helper.start(applicationContext)
                    checkShade()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (state) {
                    S.TRACK -> if (config.quickSwitch && !shadeOpen && x - sx >= sideLen() &&
                        (x - sx) > 1.5f * (sy - y).coerceAtLeast(0f)
                    ) {
                        // swiping right along the bottom: show the previous app
                        state = S.SIDE
                        PeekBridge.begin(sx, sy)
                        PeekBridge.move(x, y)
                        openRecents(true, true)
                    } else if (sy - y >= lengthPx) {
                        state = S.SWIPED
                        buzz(false)
                        ax = x; ay = y
                        handler.postDelayed(holdRunnable, holdMs)
                    }

                    S.SWIPED -> if (hypot(x - ax, y - ay) > slopPx * 2f) {
                        // still moving: restart the hold timer from here
                        ax = x; ay = y
                        handler.removeCallbacks(holdRunnable)
                        handler.postDelayed(holdRunnable, holdMs)
                    }

                    S.PEEK, S.SIDE -> PeekBridge.move(x, y)
                    else -> {}
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
                // Swiped up and let go without holding: go home.
                if (state == S.SWIPED && action == MotionEvent.ACTION_UP) {
                    homeOrCollapse()
                }
                if (state == S.PEEK || state == S.SIDE) PeekBridge.release()
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

    /** Asks the system to put the focused app into split screen. */
    fun splitScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

    /** A short vibration (when haptics are on): a light tick, or a firmer click. */
    private fun buzz(firm: Boolean) {
        if (!config.haptics) return
        try {
            val v = getSystemService(android.os.Vibrator::class.java) ?: return
            v.vibrate(
                android.os.VibrationEffect.createPredefined(
                    if (firm) android.os.VibrationEffect.EFFECT_HEAVY_CLICK else android.os.VibrationEffect.EFFECT_TICK
                )
            )
        } catch (e: Throwable) {
            // no vibration then
        }
    }

    /** Misclick protection: the first swipe only shows a toast, repeating it within 2.5 s does it. */
    private fun armed(msg: String): Boolean {
        if (!config.misclick || !navHidden) return true // only where the navigation bar is hidden
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastArm <= 2500L) {
            lastArm = 0L
            return true
        }
        lastArm = now
        handler.post { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }
        return false
    }

    private fun sideLen() = 48f * resources.displayMetrics.density

    private fun openRecents(peek: Boolean, quick: Boolean = false) {
        if (RecentsActivity.visible) return
        val i = Intent(this, RecentsActivity::class.java)
            .setAction("com.twixxty.rootrecents.SHOW")
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            .putExtra("peek", peek)
            .putExtra("quick", quick)
        try {
            startActivity(i)
        } catch (e: Exception) {
            // handled below
        }
        // Background starts can be silently blocked: if nothing showed up, start it through root.
        handler.postDelayed({
            if (!RecentsActivity.visible && (state == S.PEEK || state == S.SIDE || state == S.DONE || state == S.IDLE)) {
                Thread { Actions.startRecents((if (peek) "--ez peek true " else "") + (if (quick) "--ez quick true" else "")) }.start()
            }
        }, 350)
    }
}
