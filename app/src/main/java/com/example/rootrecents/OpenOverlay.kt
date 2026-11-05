package com.example.rootrecents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.WindowManager
import kotlin.math.max

/**
 * The opening animation lives in its own overlay window, so it keeps playing on top of the app
 * after the switcher has closed: the card grows to fill the screen (corners follow the screen's
 * radius), gets blurred or washed with a colour while it does, then fades away.
 */
object OpenOverlay {

    private class CardView(
        ctx: Context,
        val bmp: Bitmap?,
        val icon: Drawable?,
        val from: RectF,
        val fromRadius: Float,
        val toRadius: Float,
        val screen: RectF,
        val washColor: Int
    ) : View(ctx) {
        var t = 0f
        var wash = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val path = Path()
        private val rect = RectF()
        private val fill = Paint().apply { color = 0xFF1E1E1E.toInt() }
        private val washPaint = Paint()

        override fun onDraw(canvas: Canvas) {
            rect.set(
                from.left + (screen.left - from.left) * t,
                from.top + (screen.top - from.top) * t,
                from.right + (screen.right - from.right) * t,
                from.bottom + (screen.bottom - from.bottom) * t
            )
            val r = (fromRadius + (toRadius - fromRadius) * t).coerceAtLeast(0f)
            path.reset()
            path.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(path)
            if (bmp != null) {
                // centre-crop the screenshot into the current rectangle
                val s = max(rect.width() / bmp.width, rect.height() / bmp.height)
                val w = bmp.width * s
                val h = bmp.height * s
                val l = rect.centerX() - w / 2f
                val tp = rect.centerY() - h / 2f
                canvas.save()
                canvas.translate(l, tp)
                canvas.scale(s, s)
                canvas.drawBitmap(bmp, 0f, 0f, paint)
                canvas.restore()
            } else {
                canvas.drawRect(rect, fill)
                icon?.let {
                    val half = rect.width() / 4f
                    it.setBounds(
                        (rect.centerX() - half).toInt(), (rect.centerY() - half).toInt(),
                        (rect.centerX() + half).toInt(), (rect.centerY() + half).toInt()
                    )
                    it.draw(canvas)
                }
            }
            if (wash > 0f) {
                washPaint.color = washColor
                washPaint.alpha = (wash.coerceIn(0f, 1f) * 255f).toInt()
                canvas.drawRect(rect, washPaint)
            }
            canvas.restore()
        }
    }

    /**
     * @param effect 0 blur, 1 colour wash, 2 nothing
     * @param from the card's thumbnail rectangle in screen coordinates
     * @param blurPx maximum blur radius in pixels
     * @return false if the overlay could not be shown (caller should animate in its own window)
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
        omegaScale: Float
    ): Boolean {
        val app = ctx.applicationContext
        if (!android.provider.Settings.canDrawOverlays(app)) return false
        val wm = app.getSystemService(WindowManager::class.java) ?: return false

        val screen = RectF()
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            screen.set(0f, 0f, b.width().toFloat(), b.height().toFloat())
        } else {
            val dm = app.resources.displayMetrics
            screen.set(0f, 0f, dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        }

        val view = CardView(app, bmp, icon, from, fromRadius, toRadius, screen, washColor)
        val lp = WindowManager.LayoutParams(
            screen.width().toInt(), screen.height().toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            return false
        }

        val physics = Physics()
        var removed = false
        fun remove() {
            if (removed) return
            removed = true
            try {
                wm.removeViewImmediate(view)
            } catch (e: Exception) {
                // already gone
            }
        }

        var fading = false
        var lastBlur = -1
        physics.onFrameEnd = { view.invalidate() }
        physics.start(SpringMotion(0f, 0f, 1f, 13f * omegaScale, 0.95f, 0.002f, 0.02f) { t ->
            view.t = t
            val e = t.coerceIn(0f, 1f)
            when (effect) {
                0 -> if (Build.VERSION.SDK_INT >= 31) {
                    val r = (blurPx * e).toInt()
                    if (r != lastBlur) {
                        lastBlur = r
                        view.setRenderEffect(
                            if (r > 0) RenderEffect.createBlurEffect(r.toFloat(), r.toFloat(), Shader.TileMode.CLAMP)
                            else null
                        )
                    }
                }
                1 -> view.wash = e * 0.85f
            }
            if (!fading && t > 0.82f) {
                fading = true
                physics.start(SpringMotion(1f, 0f, 0f, 10f * omegaScale, 1f, 0.01f, 0.1f) { a ->
                    view.alpha = a.coerceIn(0f, 1f)
                    if (a <= 0.02f) remove()
                })
            }
        })
        // safety net so a stuck overlay can never stay on screen
        view.postDelayed({ remove() }, 2500)
        return true
    }
}
