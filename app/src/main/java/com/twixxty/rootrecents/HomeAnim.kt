package com.twixxty.rootrecents

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Go-home effect: the screen blurs, the system's own home animation plays underneath, and the
 * blur clears once the home screen is up. It is a see-through overlay with blur-behind (plus a
 * tint), so nothing is drawn by us except the blur and the tint.
 */
class HomeAnim(private val ctx: Context, private val config: Config) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var scrim: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var blurMax = 0
    private var tintMax = 0f
    private var lastBlur = -1

    /** Adds the (still invisible) overlay. False if it could not be shown. */
    fun show(): Boolean {
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
        val r = FrameLayout(ctx)
        val sc = View(ctx).apply {
            setBackgroundColor(config.tintColor(ctx))
            alpha = 0f
        }
        r.addView(sc, FrameLayout.LayoutParams(sw, sh))
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val canBlur = Build.VERSION.SDK_INT >= 31 && config.blur > 0 && wm.isCrossWindowBlurEnabled
        if (canBlur) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        val lp = WindowManager.LayoutParams(
            sw, sh, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) lp.fitInsetsTypes = 0
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        blurMax = if (canBlur) config.blurRadiusPx.toInt().coerceAtLeast(1) else 0
        tintMax = config.tintAlpha
        if (Build.VERSION.SDK_INT >= 31) lp.blurBehindRadius = 0
        return try {
            wm.addView(r, lp)
            root = r
            scrim = sc
            params = lp
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Sets how blurred the screen is: 0 = clear, 1 = fully blurred and tinted. */
    private fun apply(k: Float) {
        scrim?.alpha = tintMax * k
        if (Build.VERSION.SDK_INT >= 31 && blurMax > 0) {
            val px = (blurMax * k).toInt()
            if (px != lastBlur) {
                lastBlur = px
                val lp = params ?: return
                lp.blurBehindRadius = px
                try {
                    wm.updateViewLayout(root, lp)
                } catch (e: Exception) {
                    // gone
                }
            }
        }
    }

    private fun animate(from: Float, to: Float, ms: Long, done: () -> Unit) {
        if (ms <= 0L) {
            apply(to)
            done()
            return
        }
        ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { apply(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    done()
                }
            })
            start()
        }
    }

    /** Blur the screen, then [onDone] (this is when the home screen is started). */
    fun blurIn(onDone: () -> Unit) = animate(0f, 1f, config.int(Config.HOME_BLUR_IN).toLong(), onDone)

    /** Clear the blur and take the overlay away. */
    fun blurOut() = animate(1f, 0f, config.int(Config.HOME_BLUR_OUT).toLong()) { remove() }

    fun remove() {
        val r = root ?: return
        root = null
        try {
            wm.removeViewImmediate(r)
        } catch (e: Exception) {
            // already gone
        }
    }
}
