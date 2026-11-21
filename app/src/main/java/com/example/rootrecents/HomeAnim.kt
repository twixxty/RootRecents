package com.example.rootrecents

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.content.Context

/**
 * "Go home" animation. A full-screen overlay shows the foreground app's picture exactly where the
 * app is, so the real jump to the home screen underneath is invisible. Once the home screen has
 * drawn, the picture shrinks towards the upper third of the screen while the blur over the home
 * screen clears.
 */
class HomeAnim(private val ctx: Context, private val config: Config, private val shot: Bitmap) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var pic: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var blurMax = 0
    private var ran = false

    /** Adds the overlay. False if it could not be shown (then nothing was changed). */
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
        val radius = radiusPx()
        val p = ImageView(ctx).apply {
            setImageBitmap(shot)
            scaleType = ImageView.ScaleType.FIT_XY
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
        }
        r.addView(p, FrameLayout.LayoutParams(sw, sh))
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
        blurMax = if (canBlur) config.blurRadiusPx.toInt() else 0
        if (Build.VERSION.SDK_INT >= 31) lp.blurBehindRadius = blurMax
        return try {
            wm.addView(r, lp)
            root = r
            pic = p
            params = lp
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun radiusPx(): Float {
        if (config.radiusAuto && Build.VERSION.SDK_INT >= 31) {
            try {
                val insets = wm.currentWindowMetrics.windowInsets
                val rr = insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius
                if (rr != null && rr > 0) return rr.toFloat()
            } catch (e: Exception) {
                // fall through
            }
        }
        return config.int(Config.RADIUS_DP) * ctx.resources.displayMetrics.density
    }

    /** Shrinks the picture towards the upper third of the screen, unblurring what is behind it. */
    fun run() {
        if (ran) return
        ran = true
        val p = pic ?: return
        val h = p.height.toFloat().coerceAtLeast(1f)
        p.pivotX = p.width / 2f
        p.pivotY = h / 2f
        var lastBlur = blurMax
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460
            interpolator = PathInterpolator(0.22f, 0.0f, 0.0f, 1.0f)
            addUpdateListener {
                val t = it.animatedValue as Float
                val s = (1f - t).coerceAtLeast(0f)
                p.scaleX = s
                p.scaleY = s
                p.translationY = -h / 6f * t // centre travels from 1/2 to 1/3 of the screen height
                p.alpha = if (t < 0.8f) 1f else ((1f - t) / 0.2f).coerceIn(0f, 1f)
                if (Build.VERSION.SDK_INT >= 31 && blurMax > 0) {
                    val px = (blurMax * (1f - t)).toInt()
                    if (px != lastBlur) {
                        lastBlur = px
                        val lp = params
                        if (lp != null) {
                            lp.blurBehindRadius = px
                            try {
                                wm.updateViewLayout(root, lp)
                            } catch (e: Exception) {
                                // gone
                            }
                        }
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    remove()
                }
            })
            start()
        }
    }

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
