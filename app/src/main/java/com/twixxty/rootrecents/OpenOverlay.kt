package com.twixxty.rootrecents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * The opening animation lives in its own overlay window, so it keeps playing on top of the app
 * after the switcher has closed. Everything in it is fully opaque until the final fade:
 * the card grows (blurring, or washing to a colour) until it fills the screen with the screen's
 * corner radius, the app is started underneath, and only then does the cover fade away.
 *
 * The blur is applied to the picture itself (not to a see-through window), so its edges stay
 * solid; the rounded corners are cut afterwards by clipping the container.
 */
object OpenOverlay {

    /** Lets the caller say when the app has actually started, which is when the overlay fades. */
    class Controller internal constructor() {
        internal var view: View? = null
        internal var launched = false
        internal var filled = false
        internal var tryFade: (() -> Unit)? = null

        /** Call (from any thread) once the app has been started. */
        fun launched() {
            view?.post {
                launched = true
                tryFade?.invoke()
            }
        }
    }

    private class Holder(ctx: Context) : FrameLayout(ctx) {
        val rect = RectF()
        var radius = 0f
    }

    /**
     * Order of events: the card grows and blurs until it fills the screen, [onFilled] is called
     * (start the app now), and only after [Controller.launched] does the overlay fade away.
     *
     * @param effect 0 blur, 1 colour wash, 2 nothing
     * @param from the card's thumbnail rectangle in screen coordinates
     * @param blurPx maximum blur radius in pixels
     * @return null if the overlay could not be shown (caller should animate in its own window)
     */
    fun play(
        ctx: Context,
        bmp: Bitmap?,
        icon: Drawable?,
        from: RectF,
        fromRadius: Float,
        toRadius: Float,
        effect: Int,
        washColor: Int,
        blurPx: Float,
        omegaScale: Float,
        holdMs: Int,
        fadeMs: Int,
        onFilled: () -> Unit
    ): Controller? {
        val app = ctx.applicationContext
        if (!android.provider.Settings.canDrawOverlays(app)) return null
        val wm = app.getSystemService(WindowManager::class.java) ?: return null

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

        // root -> holder (rounded clip) -> picture (+ colour wash)
        val root = FrameLayout(app)
        val holder = Holder(app)
        holder.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val h = view as Holder
                outline.setRoundRect(
                    h.rect.left.toInt(), h.rect.top.toInt(),
                    h.rect.right.toInt(), h.rect.bottom.toInt(), h.radius
                )
            }
        }
        holder.clipToOutline = true

        val pic = ImageView(app)
        if (bmp != null) {
            pic.scaleType = ImageView.ScaleType.CENTER_CROP
            pic.setImageBitmap(bmp)
        } else {
            pic.setBackgroundColor(0xFF1E1E1E.toInt())
            pic.scaleType = ImageView.ScaleType.CENTER_INSIDE
            pic.setImageDrawable(icon)
            pic.setPadding(sw / 4, 0, sw / 4, 0)
        }
        pic.pivotX = 0f
        pic.pivotY = 0f
        holder.addView(pic, FrameLayout.LayoutParams(sw, sh))

        val wash = View(app)
        wash.setBackgroundColor(washColor or 0xFF000000.toInt())
        wash.alpha = 0f
        wash.pivotX = 0f
        wash.pivotY = 0f
        holder.addView(wash, FrameLayout.LayoutParams(sw, sh))

        // A solid scrim behind the card fades in as it grows, so nothing from the switcher or the
        // old screen shows around it (or in its rounded corners) by the time it fills the screen.
        val scrim = View(app)
        scrim.setBackgroundColor(washColor or 0xFF000000.toInt())
        scrim.alpha = 0f
        root.addView(scrim, FrameLayout.LayoutParams(sw, sh))
        root.addView(holder, FrameLayout.LayoutParams(sw, sh))

        val lp = WindowManager.LayoutParams(
            sw, sh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.alpha = 1f
        // It takes the touches while it is up: a pass-through ("not touchable") overlay can be
        // rendered see-through by the system, and taps during the transition would be accidents.
        root.setOnTouchListener { _, _ -> true }
        if (Build.VERSION.SDK_INT >= 30) lp.fitInsetsTypes = 0
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // first frame: exactly where the card is
        fun apply(t: Float) {
            val l = from.left + (0f - from.left) * t
            val tp = from.top + (0f - from.top) * t
            val r = from.right + (sw - from.right) * t
            val b = from.bottom + (sh - from.bottom) * t
            holder.rect.set(l, tp, r, b)
            holder.radius = (fromRadius + (toRadius - fromRadius) * t).coerceAtLeast(0f)
            val sx = (r - l) / sw
            val sy = (b - tp) / sh
            for (v in arrayOf(pic, wash)) {
                v.scaleX = sx
                v.scaleY = sy
                v.translationX = l
                v.translationY = tp
            }
            holder.invalidateOutline()
        }
        apply(0f)

        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            return null
        }

        val ctl = Controller()
        ctl.view = root
        val physics = Physics()
        var removed = false
        fun remove() {
            if (removed) return
            removed = true
            try {
                wm.removeViewImmediate(root)
            } catch (e: Exception) {
                // already gone
            }
        }

        var fading = false
        var lastBlur = -1
        fun fade() {
            if (fading) return
            fading = true
            // critically damped: ~97% done after about 5/omega seconds
            val omega = 5f / (fadeMs.coerceAtLeast(40) / 1000f)
            physics.start(SpringMotion(1f, 0f, 0f, omega, 1f, 0.01f, 0.1f) { a ->
                root.alpha = a.coerceIn(0f, 1f)
                if (a <= 0.02f) remove()
            })
        }
        ctl.tryFade = {
            // a short beat so the app's first frame is on screen before the cover goes away
            if (ctl.filled && ctl.launched) root.postDelayed({ fade() }, holdMs.toLong())
        }

        physics.start(SpringMotion(0f, 0f, 1f, 13f * omegaScale, 0.95f, 0.002f, 0.02f) { t ->
            val e = t.coerceIn(0f, 1f)
            apply(t.coerceIn(0f, 1.02f))
            scrim.alpha = ((e - 0.05f) / 0.7f).coerceIn(0f, 1f)
            when (effect) {
                0 -> if (Build.VERSION.SDK_INT >= 31) {
                    val r = (blurPx * e).toInt()
                    if (r != lastBlur) {
                        lastBlur = r
                        pic.setRenderEffect(
                            if (r > 0) RenderEffect.createBlurEffect(r.toFloat(), r.toFloat(), Shader.TileMode.CLAMP)
                            else null
                        )
                    }
                }
                1 -> wash.alpha = e // solid colour by the time it fills the screen
            }
            if (!ctl.filled && t > 0.97f) {
                ctl.filled = true
                onFilled()
                ctl.tryFade?.invoke()
            }
        })
        // never leave the screen covered if the app is slow or never reports back
        root.postDelayed({ ctl.launched = true; ctl.filled = true; fade() }, 3500)
        return ctl
    }
}
