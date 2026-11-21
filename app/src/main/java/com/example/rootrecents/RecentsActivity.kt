package com.example.rootrecents

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.min

/**
 * Full-screen, translucent overlay: the app switcher.
 * Open it with:  su -c "am start -a com.example.rootrecents.SHOW"
 *
 * The cards live in [CarouselView] (all physics). This class owns the window: blur, tint, loading
 * the task list, and what happens when you tap, dismiss or clear.
 */
class RecentsActivity : Activity() {

    companion object {
        /** True while the switcher is on screen (the bottom trigger steps aside then). */
        @JvmStatic
        var visible = false
            private set

        /** Root-read touches that began on the gesture handle while the switcher is open. */
        @JvmStatic
        var rawPull: ((Int, Float, Float) -> Unit)? = null

        @JvmStatic
        var onVisibility: ((Boolean) -> Unit)? = null
    }

    private lateinit var config: Config
    private lateinit var carousel: CarouselView
    private lateinit var empty: TextView
    private var bottomBtn: TextView? = null
    private var emptyM: Motion? = null

    private val dim = ColorDrawable(0x00000000)
    private val physics = Physics()

    private var blurActive = false
    private var lastBlurPx = -1
    private var backdrop = 0f
    private var backdropM: Motion? = null
    private var tint = 0

    private var launching = false
    private var closing = false

    private var loaded: List<RecentTask>? = null
    private var shown = false
    private var peekMode = false

    private val prefs by lazy { getSharedPreferences("recents", MODE_PRIVATE) }

    private val peekListener = object : PeekBridge.Listener {
        override fun onMove(fx: Float, fy: Float, dx: Float, dy: Float) {
            if (carousel.visibility == View.VISIBLE) carousel.peekMove(fx, fy, dx, dy)
        }

        override fun onRelease() {
            // with nothing to show, letting go just unblurs and closes
            if (carousel.visibility == View.VISIBLE) carousel.peekRelease() else closeAnimated()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config.get(this)
        peekMode = intent.getBooleanExtra("peek", false)
        tint = config.tintColor(this)

        window.setBackgroundDrawable(dim)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            blurActive = config.blur > 0 && windowManager.isCrossWindowBlurEnabled
        }

        setContentView(R.layout.activity_recents)
        carousel = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        carousel.alpha = 0f

        carousel.applyConfig(config)

        carousel.onCardClick = { card -> openTask(card, false) }
        carousel.onPillClick = { card -> showMenu(card) }
        carousel.onCardLongPress = { task -> openAppInfo(task) }
        carousel.onClearClick = { hideBottomButton(); carousel.clearAll() }
        carousel.onEmptyTap = { closeAnimated() }
        carousel.onDismissed = { tasks -> closeSystemTasks(tasks) }
        carousel.onAllGone = { closeAnimated() }
        carousel.onEmptying = { hideBottomButton() }
        carousel.onPullClose = { closeAnimated() }
        empty.setOnClickListener { closeAnimated() }

        setupBottomButton()
        // the blur starts right away (also while the list is still loading), so a swipe-and-hold
        // shows something immediately
        animateBackdrop(1f)
        load()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        rawPull = { a, _, y -> carousel.rawPull(a, y) }
        onVisibility?.invoke(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!closing && !launching && !PeekBridge.isActive) {
            // A fresh trigger while already open: start over.
            peekMode = intent.getBooleanExtra("peek", false)
            shown = false
            loaded = null
            carousel.alpha = 0f
            load()
        }
    }

    override fun onStop() {
        super.onStop()
        visible = false
        rawPull = null
        onVisibility?.invoke(false)
        // Behave like a system overlay: leaving it (home, another app) dismisses it.
        if (!isFinishing) finishNoAnim()
    }

    override fun onDestroy() {
        super.onDestroy()
        PeekBridge.detach(peekListener)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = closeAnimated()

    // ---------------------------------------------------------------- bottom button

    private fun setupBottomButton() {
        val mode = config.bottomButton
        if (mode == 2) return
        val d = resources.displayMetrics.density
        val b = TextView(this).apply {
            text = if (mode == 0) "⛶  Screenshot" else "✕  Clear all"
            setTextColor(MaterialYou.onDarkButton(this@RecentsActivity))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding((24 * d).toInt(), (14 * d).toInt(), (26 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 40 * d
                setColor(MaterialYou.darkButton(this@RecentsActivity))
            }
            alpha = 0f
            visibility = View.GONE // shown once there are cards
            elevation = 6 * d
            setOnClickListener {
                if (mode == 0) {
                    carousel.focusedCard()?.let { openTask(it, true) }
                } else {
                    hideBottomButton()
                    carousel.clearAll()
                }
            }
        }
        val h = resources.displayMetrics.heightPixels
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = (h * 0.1f - 26 * d).toInt().coerceAtLeast((24 * d).toInt()) }
        (findViewById<ViewGroup>(R.id.root)).addView(b, lp)
        bottomBtn = b
    }

    private fun hideBottomButton() {
        bottomBtn?.visibility = View.GONE
    }

    // ---------------------------------------------------------------- loading

    private fun load() {
        val appCtx = applicationContext
        // started by the trigger the moment the finger touched the strip, or start it now
        val job = Prefetch.take() ?: Prefetch.begin(appCtx)
        Thread {
            if (!android.provider.Settings.canDrawOverlays(appCtx)) Actions.ensureOverlayPermission(appCtx)
        }.start()
        Thread {
            val res = job.await()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (res == null || res.noRoot) {
                    empty.setText(if (res?.noRoot == true) R.string.no_root else R.string.no_recents)
                    showEmpty()
                } else {
                    loaded = res.tasks
                    maybeShow()
                }
            }
            // Screenshots come in a moment later and are swapped into the visible cards.
            val thumbs = job.awaitThumbs()
            if (thumbs.isNotEmpty()) runOnUiThread {
                if (!isDestroyed && !isFinishing) carousel.setThumbs(thumbs)
            }
        }.start()
    }

    private fun maybeShow() {
        val tasks = loaded ?: return
        if (shown) return
        shown = true
        show(tasks)
    }

    private fun show(tasks: List<RecentTask>) {
        if (tasks.isEmpty()) {
            showEmpty()
            return
        }
        if (closing) return
        empty.visibility = View.GONE
        bottomBtn?.visibility = View.VISIBLE
        carousel.setTasks(tasks)
        carousel.post {
            if (peekMode) {
                // The finger is still down on the trigger: the cards peek in and follow it.
                carousel.alpha = 1f
                carousel.peekStart()
                animateBackdrop(1f)
                PeekBridge.attach(peekListener)
            } else {
                val style = when (config.enterStyle) {
                    0 -> CarouselView.EnterStyle.RISE
                    2 -> CarouselView.EnterStyle.SLIDE_RIGHT
                    3 -> CarouselView.EnterStyle.ZOOM
                    else -> CarouselView.EnterStyle.SLIDE_LEFT
                }
                // Entry offsets are applied first, then the view is revealed: no flash of the
                // cards sitting at rest before they animate in.
                carousel.playEnter(style)
                carousel.alpha = 1f
                animateBackdrop(1f)
            }
        }
    }

    private fun showEmpty() {
        hideBottomButton()
        carousel.visibility = View.GONE
        if (closing) return // released before the (empty) list arrived: already on its way out
        empty.visibility = View.VISIBLE
        empty.alpha = 0f
        emptyM = physics.start(SpringMotion(0f, 0f, 1f, 12f, 1f, 0.002f, 0.02f) { empty.alpha = it })
        animateBackdrop(1f)
        if (peekMode) PeekBridge.attach(peekListener)
    }

    // ---------------------------------------------------------------- backdrop (blur + tint)

    private fun setBackdrop(p: Float) {
        backdrop = p
        val c = p.coerceIn(0f, 1f)
        // Without blur, darken more so the cards still stand out.
        val a = min(0.85f, config.tintAlpha * (if (blurActive) 1f else 1.5f))
        dim.color = (tint and 0x00FFFFFF) or ((a * c * 255f).toInt() shl 24)
        bottomBtn?.alpha = c
        if (blurActive && Build.VERSION.SDK_INT >= 31) {
            val px = (config.blurRadiusPx * c).toInt()
            if (px != lastBlurPx) {
                lastBlurPx = px
                val lp = window.attributes
                lp.blurBehindRadius = px
                window.attributes = lp
            }
        }
    }

    private fun animateBackdrop(to: Float, onEnd: (() -> Unit)? = null) {
        physics.cancel(backdropM)
        val m = SpringMotion(backdrop, 0f, to, 12f, 1f, 0.002f, 0.02f) { setBackdrop(it) }
        m.onEnd = onEnd
        backdropM = m
        physics.start(m)
    }

    // ---------------------------------------------------------------- actions

    private fun closeAnimated() {
        if (closing || launching) return
        closing = true
        // close once both the cards have left and the blur is gone
        var pending = 2
        val one = { if (--pending == 0) finishNoAnim() }
        if (carousel.visibility == View.VISIBLE && carousel.taskCount() > 0) carousel.playExit { one() } else one()
        physics.cancel(emptyM)
        if (empty.visibility == View.VISIBLE) {
            physics.start(SpringMotion(empty.alpha, 0f, 0f, 16f, 1f, 0.01f, 0.05f) { empty.alpha = it })
        }
        animateBackdrop(0f) { one() }
        carousel.postDelayed({ finishNoAnim() }, 1400) // safety net
    }

    /** The screen's corner radius in pixels: detected (Android 12+), or the manual setting. */
    private fun screenRadiusPx(): Float {
        if (config.radiusAuto && Build.VERSION.SDK_INT >= 31) {
            val insets = window.decorView.rootWindowInsets
            val r = insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius
            if (r != null && r > 0) return r.toFloat()
        }
        return config.int(Config.RADIUS_DP) * resources.displayMetrics.density
    }

    /**
     * Tap on a card. In order: the card grows and blurs until it fills the screen (in an overlay
     * window), then the app is started, then the overlay fades away to reveal it.
     */
    private fun openTask(card: CarouselView.Card, thenScreenshot: Boolean) {
        val task = card.task ?: return
        if (launching || closing) return
        launching = true

        val appCtx = applicationContext
        fun startApp(done: () -> Unit) {
            // NO_ANIMATION + -W: Android's own slide never plays and we learn when the app is up.
            Thread {
                Actions.launch(appCtx, task)
                done()
                if (thenScreenshot) {
                    Thread.sleep(900)
                    Actions.screenshotKey()
                }
            }.start()
        }

        val rect = carousel.thumbRect(card)
        val scale = card.view.scaleX
        var ctl: OpenOverlay.Controller? = null
        ctl = OpenOverlay.play(
            appCtx, card.bmp ?: task.thumb, task.icon, rect, carousel.cardRadiusPx() * scale,
            screenRadiusPx(), config.openEffect, tint, config.blurRadiusPx * 0.7f,
            config.int(Config.OPEN_SPEED) / 100f, config.int(Config.OPEN_HOLD), config.int(Config.OPEN_FADE)
        ) {
            // The screen is covered: now it is safe to start the app and let this window go.
            startApp { ctl?.launched() }
            finishNoAnim()
        }
        if (ctl != null) {
            carousel.overlayHandoff(card) { }
        } else {
            // No overlay permission: grow the card inside this window instead.
            carousel.openCard(card) {
                startApp { }
                animateBackdrop(0f)
                carousel.postDelayed({ finishNoAnim() }, 260)
            }
        }
        carousel.postDelayed({ finishNoAnim() }, 4000) // safety net
    }

    /** The pill on a card: a small Material You menu under it. */
    private fun showMenu(card: CarouselView.Card) {
        val task = card.task ?: return
        val anchor = (if (card.pill?.visibility == View.VISIBLE) card.pill else card.header) ?: return
        val d = resources.displayMetrics.density
        val fg = MaterialYou.onContainer(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 22 * d
                setColor(MaterialYou.surface(this@RecentsActivity))
            }
        }
        val pw = PopupWindow(content, (210 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(0))
        pw.elevation = 18 * d
        pw.isOutsideTouchable = true

        val ripple = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val rippleRes = ripple.getResourceId(0, 0)
        ripple.recycle()

        fun row(glyph: String, label: String, action: () -> Unit) {
            val disc = TextView(this).apply {
                text = glyph
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(MaterialYou.onAccent(this@RecentsActivity))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(MaterialYou.accent(this@RecentsActivity))
                }
            }
            val text = TextView(this).apply {
                this.text = label
                textSize = 15f
                setTextColor(fg)
            }
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * d).toInt(), (11 * d).toInt(), (16 * d).toInt(), (11 * d).toInt())
                if (rippleRes != 0) setBackgroundResource(rippleRes)
                addView(disc, LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()))
                addView(text, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (14 * d).toInt() })
                setOnClickListener {
                    pw.dismiss()
                    action()
                }
            }
            content.addView(r)
        }
        row("i", "App info") { openAppInfo(task) }
        row("\u2715", "Close") { carousel.dismissCard(card, -1500f) }
        row("\u25A0", "Force stop") {
            Thread { Actions.forceStop(listOf(task), emptySet()) }.start()
            carousel.dismissCard(card, -1500f)
        }

        card.chev?.text = "\u25B4"
        pw.setOnDismissListener { card.chev?.text = "\u25BE" }

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val scale = card.view.scaleX
        pw.showAtLocation(
            anchor, Gravity.NO_GRAVITY, loc[0], loc[1] + (anchor.height * scale).toInt() + (6 * d).toInt()
        )
        // springs open from the pill's corner
        content.pivotX = 0f
        content.pivotY = 0f
        content.alpha = 0f
        physics.start(SpringMotion(0f, 0f, 1f, 22f, 0.75f, 0.002f, 0.02f) { t ->
            val k = 0.8f + 0.2f * t
            content.scaleX = k
            content.scaleY = k
            content.alpha = t.coerceIn(0f, 1f)
        })
    }

    /** Long-press on the app icon / name opens that app's system "App info" page. */
    private fun openAppInfo(task: RecentTask) {
        if (launching || closing) return
        Thread { Actions.openAppInfo(task) }.start()
        closeAnimated()
    }

    /** Remove from the real recents list (and optionally force-stop), then remember it. */
    private fun closeSystemTasks(tasks: List<RecentTask>) {
        val s = HashSet(prefs.getStringSet("hidden", emptySet()) ?: emptySet())
        tasks.forEach { s.add(it.id.toString()) }
        prefs.edit().putStringSet("hidden", s).apply()

        val appCtx = applicationContext
        val doStop = config.forceStop
        val never = config.neverStopSet
        Thread {
            Actions.removeTasks(appCtx, tasks.map { it.id })
            if (doStop) Actions.forceStop(tasks, never)
        }.start()
    }

    private fun finishNoAnim() {
        if (isFinishing) return
        finish()
        overridePendingTransition(0, 0)
    }
}
