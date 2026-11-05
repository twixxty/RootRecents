package com.example.rootrecents

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A thin touch strip along the bottom edge of the screen (an overlay window).
 *
 *  - swipe up (the configured length) and let go: opens recents
 *  - swipe up and hold still (the configured time): the screen blurs, the cards peek in from the
 *    left and follow your finger until you let go (this service keeps the touch stream and hands
 *    it to the recents screen through [PeekBridge])
 */
class TriggerService : Service() {

    companion object {
        private const val CHANNEL = "trigger"

        fun start(ctx: Context) {
            val i = Intent(ctx, TriggerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TriggerService::class.java))
        }
    }

    private enum class S { IDLE, TRACK, SWIPED, PEEK, DONE }

    private lateinit var wm: WindowManager
    private lateinit var config: Config
    private val handler = Handler(Looper.getMainLooper())
    private var strip: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private var state = S.IDLE
    private var gestureActive = false
    private var sx = 0f
    private var sy = 0f
    private var ax = 0f
    private var ay = 0f
    private var lengthPx = 0f
    private var holdMs = 350L
    private var peekOn = true
    private var pendingRebuild = false
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        RecentsActivity.onVisibility = { updateTouchable() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        config = Config.get(this)
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (gestureActive) {
            // Rebuilding now would remove the strip while a finger is on it (the peek would never
            // get its release), so wait until the gesture is over.
            pendingRebuild = true
            return START_STICKY
        }
        buildStrip()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        RecentsActivity.onVisibility = null
        removeStrip()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Bottom trigger", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Root Recents trigger is active")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun buildStrip() {
        removeStrip()
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
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
            stopSelf()
        }
    }

    private fun goHome() {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (e: Exception) {
            Thread {
                Root.exec("am start -a android.intent.action.MAIN -c android.intent.category.HOME -f 0x10000000")
            }.start()
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
                if (state == S.SWIPED && e.actionMasked == MotionEvent.ACTION_UP) goHome()
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
            Thread { Actions.startRecents(if (peek) "--ez peek true" else "") }.start()
        }
    }
}
