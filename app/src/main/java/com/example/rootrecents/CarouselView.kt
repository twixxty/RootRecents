package com.example.rootrecents

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * The app switcher itself. Every card is placed from one continuous scroll position [p] and every
 * movement is a physics [Motion] (no tweens, no RecyclerView).
 *
 *  - NORMAL mode: a flat row of cards with an Android 16 style pill on each card.
 *  - STACK mode: iOS-style deck. Newer cards (right) sit in front of the focused card, older ones
 *    (left) are tucked behind it. Stacking order never changes and it never snaps.
 *
 * Extras in both modes: neighbours of a card swiped up lift with it, pulling up from the bottom
 * closes, and a "peek" mode lets the bottom trigger drag the cards around while held.
 */
class CarouselView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    FrameLayout(ctx, attrs) {

    enum class Mode { NORMAL, STACK }
    enum class EnterStyle { RISE, SLIDE_LEFT, SLIDE_RIGHT, ZOOM }

    class Card(val view: View, val task: RecentTask?) {
        val header: View? = view.findViewById(R.id.header)
        val icon: ImageView? = view.findViewById(R.id.icon)
        val label: TextView? = view.findViewById(R.id.label)
        val thumb: ImageView? = view.findViewById(R.id.thumb)
        val pill: View? = view.findViewById(R.id.pill)
        val picon: ImageView? = view.findViewById(R.id.picon)
        val plabel: TextView? = view.findViewById(R.id.plabel)
        val chev: TextView? = view.findViewById(R.id.chev)

        var slot = 0
        var dragY = 0f
        var lift = 0f
        var liftV = 0f
        var flipX = 0f
        var flipS = 1f
        var ex = 0f
        var ey = 0f
        var es = 1f
        var ea = 1f
        var exv = 0f
        var eyv = 0f
        var esv = 0f
        var openT = 0f
        var leaving = false
        var tmpX = 0f
        var tmpS = 1f
        var flipXM: SpringMotion? = null
        var flipSM: SpringMotion? = null
        var dragM: Motion? = null
        val isClear get() = task == null
    }

    // ---- configuration: call applyConfig() before setTasks() ----
    var mode = Mode.NORMAL
    var snap = false
    var haptics = true
    var focusTick = true
    var showLabels = true
    var hasClear = true
    var cardPercent = 60
    private var decay = 5.7f
    private var omegaScale = 1f
    private var zeta = 0.84f
    private var stackScaleK = 0.012f
    private var stackSpread = 0.5f
    private var stackElastic = 0.5f
    private var liftI = 0.04f
    private var liftPassOn = 0.33f
    private var liftBounceZeta = 0.5f
    private var bounceAmount = 0.5f

    fun applyConfig(c: Config) {
        mode = if (c.layoutMode == 1) Mode.STACK else Mode.NORMAL
        snap = c.snap
        haptics = c.haptics
        focusTick = c.focusTick
        showLabels = c.showLabels
        hasClear = c.clearLeft
        cardPercent = c.cardSize
        decay = c.decay
        omegaScale = c.omegaScale
        zeta = c.zeta
        stackScaleK = c.stackScaleK
        stackSpread = c.stackSpread
        stackElastic = c.stackElastic
        liftI = c.liftIntensity
        liftPassOn = c.liftPassOn
        liftBounceZeta = c.liftBounceZeta
        bounceAmount = c.bounceAmount
        pullG = c.pullGravity
    }

    // ---- callbacks ----
    var onCardClick: ((Card) -> Unit)? = null
    var onPillClick: ((Card) -> Unit)? = null
    var onCardLongPress: ((RecentTask) -> Unit)? = null
    var onClearClick: (() -> Unit)? = null
    var onEmptyTap: (() -> Unit)? = null
    var onDismissed: ((List<RecentTask>) -> Unit)? = null
    var onAllGone: (() -> Unit)? = null
    var onPullClose: (() -> Unit)? = null

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density

    /** The real size of the screen (the carousel fills it once laid out). */
    private fun realSize(): IntArray {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            return intArrayOf(b.width(), b.height())
        }
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(m)
        return intArrayOf(m.widthPixels, m.heightPixels)
    }

    private fun sw(): Int = if (width > 0) width else realSize()[0]
    private fun sh(): Int = if (height > 0) height else realSize()[1]

    private val physics = Physics()
    private val cards = ArrayList<Card>()
    private val leavingCards = ArrayList<Card>()
    private var p = 0f
    private var scrollM: Motion? = null

    // stack mode: a single "soft" scroll position that lags behind p; cards further from the focus
    // follow it more, so quick flicks stretch the deck.
    private var sq = 0f
    private var sqv = 0f

    // geometry
    private var cw = 0
    private var cardH = 0
    private var headerH = 0
    private var thumbH = 0
    private var topPad = 0
    private var pitchN = 0f
    private var pitchS = 0f
    private var leftMax = 0f
    private var leftStep = 0f
    private var focusShift = 0f
    private var stretch = 0f
    private var pullG = 13f

    // outputs of place()
    private var outX = 0f
    private var outS = 1f

    // global animation state
    private var busy = false
    private var exitT = 0f
    private var fadeOthers = 0f
    private var openingCard: Card? = null
    private var openS = 1f
    private var openY = 0f
    private var goneNotified = false

    // pulling up from the bottom
    private var pullRaw = 0f
    private var pullY = 0f
    private var pullM: Motion? = null

    // peek (bottom trigger hold)
    private var peeking = false
    private var peekReleased = false
    private var peekBase = 0f
    private var peekFx = 0f
    private var peekFy = 0f
    private var peekDx = 0f
    private var peekDy = 0f

    // lift of the neighbours of a dragged card
    private var liftSrc: Card? = null
    private var liftAnchor = 0
    private var liftZeta = 0.92f
    private var liftOmega = 36f

    // focus haptic
    private var lastFocus = -1
    private var lastTickNs = 0L

    // touch
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val maxFling = ViewConfiguration.get(ctx).scaledMaximumFlingVelocity.toFloat()
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var hit: Card? = null
    private var hitHeader = false
    private var wasScrolling = false
    private var gesture = G_IDLE

    private val longPress = Runnable {
        val c = hit
        val t = c?.task
        if (gesture == G_UNDECIDED && t != null) {
            gesture = G_CONSUMED
            haptic()
            onCardLongPress?.invoke(t)
        }
    }

    init {
        physics.onFrameEnd = { applyPositions() }
        clipChildren = false
        clipToPadding = false
    }

    companion object {
        private const val G_IDLE = 0
        private const val G_UNDECIDED = 1
        private const val G_SCROLL = 2
        private const val G_CARD = 3
        private const val G_CONSUMED = 4
        private const val G_PULL = 5
        private const val SUBSTEP = 1f / 240f
    }

    // ================================================================ setup

    private fun computeGeometry() {
        val w = sw()
        val h = sh()
        cw = (w * cardPercent / 100f).toInt()
        thumbH = (cw.toLong() * h / w).toInt()
        headerH = if (mode == Mode.STACK) dp(40f).toInt() else 0
        cardH = headerH + thumbH
        topPad = (h - cardH) / 2
        pitchN = cw + dp(14f)
        // iOS-style deck (see the reference): the focused card sits a bit right of centre with its
        // left edge at ~22% of the width, the next card overlaps its right side, and older cards
        // fan out behind it to the left, staying on screen so their icons stay visible.
        val l0 = w * 0.22f
        focusShift = if (mode == Mode.STACK) l0 + cw / 2f - w / 2f else 0f
        pitchS = cw * (0.62f + 0.24f * stackSpread)
        leftMax = max(dp(30f), l0 - dp(10f))
    }

    /** [tasks] must be ordered oldest -> newest. */
    fun setTasks(tasks: List<RecentTask>) {
        computeGeometry()
        physics.cancelAll()
        removeAllViews()
        cards.clear()
        leavingCards.clear()
        goneNotified = false
        busy = false
        exitT = 0f
        fadeOthers = 0f
        openingCard = null
        liftSrc = null
        pullY = 0f
        peeking = false

        val inflater = LayoutInflater.from(context)
        if (hasClear) addCard(Card(inflater.inflate(R.layout.item_clear, this, false), null))
        for (t in tasks) {
            val c = Card(inflater.inflate(R.layout.item_task, this, false), t)
            bind(c)
            addCard(c)
        }
        cards.forEachIndexed { i, c -> c.slot = i }
        p = max(0, cards.size - 1).toFloat()
        sq = p
        sqv = 0f
        lastFocus = p.roundToInt()
        requestLayout()
        applyPositions()
    }

    private fun addCard(c: Card) {
        addView(c.view, FrameLayout.LayoutParams(cw, cardH))
        cards.add(c)
    }

    private fun bind(c: Card) {
        val t = c.task ?: return
        val stack = mode == Mode.STACK

        c.header!!.layoutParams.height = headerH
        c.header.visibility = if (stack && showLabels) View.VISIBLE else View.GONE
        c.label!!.text = t.label
        c.icon!!.setImageDrawable(t.icon)

        c.pill!!.visibility = if (!stack && showLabels) View.VISIBLE else View.GONE
        c.plabel!!.text = t.label
        c.plabel.maxWidth = (cw - dp(90f)).toInt()
        c.picon!!.setImageDrawable(t.icon)
        c.pill.background = GradientDrawable().apply {
            cornerRadius = dp(17f)
            setColor(MaterialYou.container(context))
        }
        c.plabel.setTextColor(MaterialYou.onContainer(context))
        c.chev?.setTextColor(MaterialYou.onContainer(context))

        val th = c.thumb!!
        (th.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = headerH
            height = thumbH
        }
        val radius = dp(26f)
        th.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        th.clipToOutline = true
        if (t.thumb != null) {
            th.scaleType = ImageView.ScaleType.CENTER_CROP
            th.setImageBitmap(t.thumb)
        } else {
            // No screenshot on disk yet: show the icon big instead.
            th.scaleType = ImageView.ScaleType.CENTER_INSIDE
            th.setImageDrawable(t.icon)
            th.setPadding(cw / 4, 0, cw / 4, 0)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
        val ws = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(cardH, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(ws, hs)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = (r - l - cw) / 2
        for (i in 0 until childCount) {
            getChildAt(i).layout(left, topPad, left + cw, topPad + cardH)
        }
        applyPositions()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    // ================================================================ layout maths

    /** The scroll position this card is drawn at. */
    private fun posOf(@Suppress("UNUSED_PARAMETER") c: Card): Float = p

    /**
     * Where a card [d] steps away from the focus goes (offset from the screen centre and scale).
     * Fills outX / outS. Stack: right cards step out linearly, left cards bunch up behind. The two
     * halves meet with the same slope so a scroll never kinks at the focus.
     */
    private fun place(d: Float) {
        if (mode == Mode.NORMAL) {
            outX = d * pitchN
            outS = 1f
        } else if (d >= 0f) {
            // the deck breathes a little while it moves fast (elasticity)
            outX = pitchS * d * (1f + stretch) + focusShift
            outS = 1f
        } else {
            val ad = -d
            val sc = max(0.9f, 1f - stackScaleK * ad)
            // same slope as the right side at the focus, then settling at the left limit
            val k = pitchS / leftMax
            outS = sc
            outX = -leftMax * (1f - exp(-k * ad)) - (1f - sc) * cw / 2f + focusShift
        }
    }

    private fun applyPositions() {
        val pullS = (-pullY / sh() * 0.45f).coerceIn(0f, 0.12f)
        stretch = if (mode == Mode.STACK) stackElastic * 0.4f * tanh(abs(p - sq) * 3f) else 0f
        for (c in cards) {
            val d = c.slot - posOf(c)
            place(d)
            var x = outX + c.flipX + c.ex
            var s = outS * c.flipS * c.es * (1f - pullS)
            var y = c.dragY + c.ey + c.lift + pullY
            var a = c.ea
            val v = c.view

            if (mode == Mode.STACK) {
                // The icon always stays; the title stays while a card is at or in front of the
                // focus, and fades out only as the card is tucked behind to the left.
                c.header?.alpha = 1f
                c.label?.alpha = if (d >= 0f) 1f else (1f + d * 1.6f).coerceIn(0f, 1f)
            }
            // "Clear all" at the left end is only there once you pull beyond the first app.
            if (c.isClear) a *= ((1f - p) / 0.4f).coerceIn(0f, 1f)

            val opening = openingCard
            if (opening != null) {
                if (c === opening) {
                    val t = c.openT
                    x += (0f - x) * t
                    y += (openY - y) * t
                    s += (openS - s) * t
                    c.header?.alpha = (1f - t * 3f).coerceIn(0f, 1f)
                    c.pill?.alpha = (1f - t * 3f).coerceIn(0f, 1f)
                } else {
                    a *= 1f - fadeOthers
                }
            }
            if (exitT > 0f) {
                s *= 1f + 0.06f * exitT
                a *= 1f - exitT
            }

            v.translationX = x
            v.translationY = y
            v.scaleX = s
            v.scaleY = s
            // Stacking order is fixed: the further right (newer) a card is, the further in front.
            v.translationZ = if (mode == Mode.STACK) c.slot * 2f else 0f
            v.alpha = a.coerceIn(0f, 1f)
        }
    }

    // ================================================================ scroll position

    private fun setP(v: Float) {
        p = v
        if (mode == Mode.STACK) {
            physics.start(followMotion)
        } else if (focusTick && haptics) {
            val f = v.roundToInt().coerceIn(0, max(0, cards.size - 1))
            if (f != lastFocus) {
                if (lastFocus >= 0) {
                    val now = System.nanoTime()
                    if (now - lastTickNs > 28_000_000L) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        lastTickNs = now
                    }
                }
                lastFocus = f
            }
        }
    }

    /** Stack mode: the soft position chases the real one on a spring (looser = more elastic). */
    private val followMotion = object : Motion() {
        override fun step(dt: Float): Boolean {
            val n = max(1, ceil(dt / SUBSTEP).toInt())
            val h = dt / n
            val tau = 0.008f + stackElastic * 0.03f
            val omega = min(90f, 1f / tau)
            val zt = 1f - 0.12f * stackElastic // never wobbles, only lags and catches up
            for (i in 0 until n) {
                val a = omega * omega * (p - sq) - 2f * zt * omega * sqv
                sqv += a * h
                sq += sqv * h
            }
            if (abs(p - sq) < 0.0008f && abs(sqv) < 0.01f) {
                sq = p
                sqv = 0f
                return false
            }
            return true
        }
    }

    // ================================================================ lifting neighbours

    /**
     * The cards next to a dragged card lift with it (nearest first); each further card follows the
     * one before it, so the nearer ones always move first, on the way up and on the way down.
     */
    private val liftMotion = object : Motion() {
        override fun step(dt: Float): Boolean {
            val src = liftSrc
            val srcIdx = if (src != null) cards.indexOf(src) else -1
            val input = if (src != null && srcIdx >= 0) {
                val drag = min(0f, src.dragY)
                val cap = cardH * 0.22f
                if (cap <= 0f) 0f else -cap * tanh(-drag * liftI / cap)
            } else 0f
            val leftStart = if (srcIdx >= 0) srcIdx - 1 else liftAnchor - 1
            val rightStart = if (srcIdx >= 0) srcIdx + 1 else liftAnchor
            val a = stepSide(leftStart, -1, input, dt)
            val b = stepSide(rightStart, 1, input, dt)
            return a || b
        }

        private fun stepSide(start: Int, dir: Int, input: Float, dt: Float): Boolean {
            val n = max(1, ceil(dt / SUBSTEP).toInt())
            val h = dt / n
            var moving = false
            var i = start
            var k = 0
            var prev: Card? = null
            while (i >= 0 && i < cards.size) {
                val c = cards[i]
                val target = if (prev == null) input else prev.lift * liftPassOn
                // nearer cards are stiffer and react first
                val omega = liftOmega * max(0.5f, 1f - 0.1f * k)
                for (j in 0 until n) {
                    val acc = -omega * omega * (c.lift - target) - 2f * liftZeta * omega * c.liftV
                    c.liftV += acc * h
                    c.lift += c.liftV * h
                }
                if (abs(c.lift - target) > 0.2f || abs(c.liftV) > 2f) moving = true
                prev = c
                i += dir
                k++
            }
            return moving
        }
    }

    private fun liftDrag(c: Card) {
        liftSrc = c
        liftAnchor = c.slot
        liftZeta = 0.92f
        liftOmega = 36f * omegaScale
        physics.start(liftMotion)
    }

    /** Released without clearing: the neighbours fall back down and bounce, nearest first. */
    private fun liftBounceBack(c: Card) {
        val idx = cards.indexOf(c)
        liftAnchor = if (idx >= 0) idx else liftAnchor
        liftSrc = null
        liftZeta = liftBounceZeta
        liftOmega = 24f * omegaScale
        // A downward kick makes the fall overshoot even when the lift itself was small.
        val strength = (abs(c.dragY) / (cardH * 0.15f)).coerceIn(0f, 1f)
        val kick = dp(150f) * bounceAmount * strength
        var k = 0
        for (dir in intArrayOf(-1, 1)) {
            var i = if (dir < 0) idx - 1 else idx + 1
            k = 0
            while (i >= 0 && i < cards.size) {
                cards[i].liftV += kick * exp(-0.5f * k)
                i += dir
                k++
            }
        }
        physics.start(liftMotion)
    }

    private fun liftFloatDown() {
        liftSrc = null // the card is gone: neighbours float gently back down
        liftZeta = 1f
        liftOmega = 15f * omegaScale
        physics.start(liftMotion)
    }

    // ================================================================ touch

    private fun pitchF() = (if (mode == Mode.STACK) pitchS else pitchN) / gain()

    /** The deck moves a bit further than the finger so it is easy to flick through. */
    private fun gain() = if (mode == Mode.STACK) 1.15f else 1f

    private fun dec() = if (mode == Mode.STACK) decay * 0.9f else decay

    private fun localX(c: Card, x: Float): Float {
        val v = c.view
        return (x - (v.left + v.width / 2f + v.translationX)) / v.scaleX + v.width / 2f
    }

    private fun localY(c: Card, y: Float): Float {
        val v = c.view
        return (y - (v.top + v.height / 2f + v.translationY)) / v.scaleY + v.height / 2f
    }

    private fun hitTest(x: Float, y: Float): Card? {
        var best: Card? = null
        var bestD = Float.MAX_VALUE
        for (c in cards) {
            val lx = localX(c, x)
            val ly = localY(c, y)
            if (lx < 0f || lx > cw || ly < 0f || ly > cardH) continue
            if (c.isClear && c.view.alpha < 0.3f) continue
            if (mode == Mode.STACK) {
                // the card drawn on top (highest slot) wins
                if (best == null || c.slot > best.slot) best = c
            } else {
                val d = abs(c.slot - p)
                if (d < bestD) {
                    bestD = d
                    best = c
                }
            }
        }
        return best
    }

    /** The "Clear all" card only reacts near its button, not on its whole card-sized slot. */
    private fun inPill(c: Card, x: Float, y: Float): Boolean {
        val lx = localX(c, x)
        val ly = localY(c, y)
        return abs(lx - cw / 2f) < dp(90f) && abs(ly - cardH / 2f) < dp(44f)
    }

    /** Is this point on the icon/name part of the card? */
    private fun inHeader(c: Card, x: Float, y: Float): Boolean {
        if (c.task == null) return false
        val lx = localX(c, x)
        val ly = localY(c, y)
        return if (mode == Mode.STACK) {
            ly <= headerH
        } else {
            val pill = c.pill ?: return false
            pill.visibility == View.VISIBLE && lx <= pill.right + dp(6f) && ly <= pill.bottom + dp(6f)
        }
    }

    private fun haptic() {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (busy) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                wasScrolling = scrollM != null
                physics.cancel(scrollM)
                scrollM = null
                physics.cancel(pullM)
                hit = hitTest(e.x, e.y)
                hit?.let { physics.cancel(it.dragM) } // grab a card that is still springing back
                hitHeader = hit?.let { inHeader(it, e.x, e.y) } ?: false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                gesture = G_UNDECIDED
                if (hitHeader) {
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }

            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                when (gesture) {
                    G_UNDECIDED -> {
                        val dx = e.x - downX
                        val dy = e.y - downY
                        if (abs(dx) > slop || abs(dy) > slop) {
                            removeCallbacks(longPress)
                            val inBottom = downY > height - dp(100f)
                            gesture = when {
                                inBottom && dy < 0f && abs(dy) > abs(dx) -> G_PULL
                                hit?.task != null && abs(dy) > abs(dx) -> G_CARD
                                else -> G_SCROLL
                            }
                            if (gesture == G_PULL) pullRaw = 0f
                            parent?.requestDisallowInterceptTouchEvent(true)
                            lastX = e.x // absorb the slop so the content doesn't jump
                            lastY = e.y
                        }
                    }

                    G_SCROLL -> {
                        scrollBy(-(e.x - lastX) / pitchF())
                        lastX = e.x
                    }

                    G_CARD -> {
                        hit?.let { dragCardBy(it, e.y - lastY) }
                        lastY = e.y
                    }

                    G_PULL -> {
                        pullBy(e.y - lastY)
                        lastY = e.y
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val cancelled = e.actionMasked == MotionEvent.ACTION_CANCEL
                tracker?.addMovement(e)
                tracker?.computeCurrentVelocity(1000, maxFling)
                val vx = if (cancelled) 0f else tracker?.xVelocity ?: 0f
                val vy = if (cancelled) 0f else tracker?.yVelocity ?: 0f
                when (gesture) {
                    G_UNDECIDED -> {
                        if (cancelled || wasScrolling) settle() else tap(e.x, e.y)
                    }

                    G_SCROLL -> releaseScroll(vx)
                    G_CARD -> hit?.let { releaseCard(it, vy) }
                    G_PULL -> releasePull(vy)
                }
                gesture = G_IDLE
                tracker?.recycle()
                tracker = null
                hit = null
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val c = hit
        when {
            c == null -> onEmptyTap?.invoke()
            c.isClear -> if (c.view.alpha > 0.5f && inPill(c, x, y)) onClearClick?.invoke() else onEmptyTap?.invoke()
            mode == Mode.NORMAL && hitHeader -> onPillClick?.invoke(c)
            else -> onCardClick?.invoke(c)
        }
    }

    // ================================================================ scrolling physics

    private fun maxP() = max(0f, (cards.size - 1).toFloat())

    /** The leftmost place the cards rest at: the first app (the "Clear all" card is beyond it). */
    private fun loRest(): Float =
        if (hasClear && cards.firstOrNull()?.isClear == true) min(1f, maxP()) else 0f

    private fun scrollBy(delta: Float) {
        val lo = loRest()
        val hi = maxP()
        // pulling past the first app toward "Clear all" is only lightly resisted
        val fLo = if (lo > 0f && p > 0f) 0.6f else 0.3f
        val np = p + delta
        setP(
            when {
                p in lo..hi -> when {
                    np < lo -> lo + (np - lo) * fLo
                    np > hi -> hi + (np - hi) * 0.3f
                    else -> np
                }
                // already past an edge: moving back is free, pulling further is resisted
                p < lo -> if (delta > 0f) p + delta else p + delta * fLo
                else -> if (delta < 0f) p + delta else p + delta * 0.3f
            }
        )
        applyPositions()
    }

    /**
     * A normal scroller: the fling carries on with the finger's speed and slows down by friction.
     * With snapping on, once it has slowed to a gentle drift it glides onto the nearest card.
     */
    private inner class SnapFling(v0: Float) : Motion() {
        private val lo = loRest()
        private val inner = ScrollMotion(p, v0, dec(), lo, maxP(), 18f, 0.01f, 0.6f) { setP(it) }

        override fun step(dt: Float): Boolean {
            val alive = inner.step(dt)
            val inside = p in lo..maxP()
            if (!alive || (inside && abs(inner.velocity) < 0.9f)) {
                val v = if (alive) inner.velocity else 0f
                // Where the remaining momentum would have carried it: never snap back the way it came.
                val target = (p + v / dec()).coerceIn(lo, maxP()).roundToInt().toFloat()
                val x0 = abs(p - target)
                val towards = (target - p) * v > 0f
                val base = 14f * omegaScale
                val omega = if (towards && x0 > 0.02f) max(base, min(80f, abs(v) / x0)) else base
                startScroll(SpringMotion(p, v, target, omega, 1f, 0.002f, 0.02f) { setP(it) })
                return false
            }
            return true
        }
    }

    private fun releaseScroll(vxPx: Float) {
        var v = -vxPx / pitchF() // cards per second
        if (mode == Mode.STACK) {
            // a hard flick can't send the deck flying through everything
            v = 9f * tanh(v / 9f)
        }
        val lo = loRest()
        if (lo > 0f && p < lo) {
            // dragged past the first app: rest on "Clear all" if pulled far enough, else spring back
            val target = if (p + v * 0.12f < 0.5f) 0f else lo
            startScroll(SpringMotion(p, v, target, 13f * omegaScale, 1f, 0.002f, 0.02f) { setP(it) })
            return
        }
        if (mode == Mode.NORMAL && snap) {
            startScroll(SnapFling(v))
        } else {
            startScroll(ScrollMotion(p, v, dec(), lo, maxP(), 18f, 0.01f, 0.6f) { setP(it) })
        }
    }

    /** Come to rest on the nearest card (normal mode with snapping), or just stop. */
    private fun settle() {
        val lo = loRest()
        if (lo > 0f && p < lo) {
            val target = if (p < 0.5f) 0f else lo
            startScroll(SpringMotion(p, 0f, target, 13f * omegaScale, 1f, 0.002f, 0.02f) { setP(it) })
        } else if (mode == Mode.NORMAL && snap) {
            val target = p.coerceIn(lo, maxP()).roundToInt().toFloat()
            startScroll(SpringMotion(p, 0f, target, 14f * omegaScale, 1f, 0.002f, 0.02f) { setP(it) })
        } else if (p < lo || p > maxP()) {
            startScroll(ScrollMotion(p, 0f, dec(), lo, maxP(), 18f, 0.01f, 0.6f) { setP(it) })
        }
    }

    private fun startScroll(m: Motion) {
        physics.cancel(scrollM)
        m.onEnd = { if (scrollM === m) scrollM = null }
        scrollM = m
        physics.start(m)
    }

    // ================================================================ vertical card drag

    private fun dragCardBy(c: Card, dy: Float) {
        val ny = c.dragY + dy
        c.dragY = when {
            ny <= 0f -> ny
            c.dragY > 0f -> c.dragY + dy * 0.28f // already pulled down: keep resisting
            else -> ny * 0.28f
        }
        liftDrag(c)
        applyPositions()
    }

    private fun releaseCard(c: Card, vy: Float) {
        val projected = c.dragY + vy * 0.18f
        val dismiss = c.task != null && (projected < -cardH * 0.33f || vy < -1100f)
        if (dismiss) {
            dismissCard(c, vy)
        } else {
            // Not far enough: it falls back with a bounce, and the neighbours follow it down.
            physics.cancel(c.dragM)
            val zc = (zeta * 0.65f).coerceIn(0.3f, 0.9f)
            val m = SpringMotion(c.dragY, vy, 0f, 15f * omegaScale, zc, 0.3f, 4f) { c.dragY = it }
            c.dragM = m
            physics.start(m)
            liftBounceBack(c)
        }
    }

    // ================================================================ pulling up from the bottom

    private var pullV = 0f
    private var pullTarget = 0f

    /** The pull follows the finger on a spring, so it has weight and a bit of lag. */
    private val pullFollow = object : Motion() {
        override fun step(dt: Float): Boolean {
            val n = max(1, ceil(dt / SUBSTEP).toInt())
            val h = dt / n
            val omega = 20f * omegaScale
            for (i in 0 until n) {
                val a = -omega * omega * (pullY - pullTarget) - 2f * 0.82f * omega * pullV
                pullV += a * h
                pullY += pullV * h
            }
            return abs(pullY - pullTarget) > 0.4f || abs(pullV) > 8f
        }
    }

    private fun pullBy(dy: Float) {
        pullRaw += dy
        if (pullRaw > 0f) pullRaw = 0f
        val cap = sh() * 0.065f
        // A small lift that follows the finger, then stiffens: elastic.
        pullTarget = -cap * tanh(-pullRaw / cap)
        physics.cancel(pullM)
        physics.start(pullFollow)
    }

    private fun releasePull(vy: Float) {
        val h = sh()
        physics.cancel(pullFollow)
        val close = -pullRaw > h * 0.12f || vy < -1000f
        physics.cancel(pullM)
        // The lift always just settles back down (critically damped when leaving); closing is the
        // cards sliding out to the left, never flying up.
        val m = SpringMotion(
            pullY, 0f, 0f, pullG * omegaScale,
            if (close) 1f else (zeta * 0.7f).coerceIn(0.4f, 0.9f), 0.3f, 4f
        ) {
            pullY = it
            applyPositions()
        }
        pullM = m
        physics.start(m)
        if (close) onPullClose?.invoke()
    }

    // ================================================================ removing cards

    fun dismissCard(c: Card, vy: Float) {
        val t = c.task ?: return
        if (c.leaving) return
        liftAnchor = c.slot
        removeCards(listOf(c), listOf(vy), 0L)
        liftFloatDown()
        onDismissed?.invoke(listOf(t))
    }

    /**
     * Takes [victims] out of the row. The rest of the cards keep exactly the pixels they were at
     * (FLIP) and then glide to their new places on springs (with a little bounce), so the gap
     * closes smoothly instead of the list jumping. Victims fly off the top on their own physics.
     */
    private fun removeCards(victims: List<Card>, vys: List<Float>, staggerMs: Long) {
        physics.cancel(scrollM)
        scrollM = null

        // 1. where every survivor is right now
        for (c in cards) {
            if (c in victims) continue
            place(c.slot - posOf(c))
            c.tmpX = outX + c.flipX
            c.tmpS = outS * c.flipS
        }
        // 2. keep the same card focused
        var shift = 0
        for (v in victims) if (v.slot < p) shift++

        for ((i, v) in victims.withIndex()) {
            cards.remove(v)
            leavingCards.add(v)
            v.leaving = true
            startLeave(v, vys.getOrElse(i) { -1500f }, i * staggerMs)
        }
        cards.forEachIndexed { i, c -> c.slot = i }
        p = (p - shift).coerceIn(0f, maxP())
        lastFocus = p.roundToInt()
        sq = p
        sqv = 0f

        // 3. animate survivors from their old pixels to the new layout
        for (c in cards) {
            place(c.slot - posOf(c))
            startFlip(c, c.tmpX - outX, c.tmpS / outS)
        }
        applyPositions()
        settle()
    }

    private fun startFlip(c: Card, fx: Float, fs: Float) {
        val vx = c.flipXM?.velocity ?: 0f
        val vs = c.flipSM?.velocity ?: 0f
        physics.cancel(c.flipXM)
        physics.cancel(c.flipSM)
        c.flipX = fx
        c.flipS = fs
        val om = 11f * omegaScale
        val zf = (zeta * 0.7f).coerceIn(0.35f, 0.9f) // a little bounce as the gap closes
        val mx = SpringMotion(fx, vx, 0f, om, zf, 0.3f, 4f) { c.flipX = it }
        val ms = SpringMotion(fs, vs, 1f, om, zf, 0.0008f, 0.01f) { c.flipS = it }
        c.flipXM = mx
        c.flipSM = ms
        physics.start(mx)
        physics.start(ms)
    }

    private fun startLeave(c: Card, vy: Float, delayMs: Long) {
        physics.cancel(c.dragM)
        val h = sh().toFloat()
        val begin = Runnable {
            val m = SpringMotion(c.dragY, min(vy, -1400f), -h * 1.35f, 7f * omegaScale, 1f, h * 0.3f, 8f) { y ->
                c.dragY = y
                c.view.translationY = y
                val al = (1f + y / (h * 0.55f)).coerceIn(0f, 1f)
                c.view.alpha = al
                if (al <= 0.02f && c.leaving) {
                    // gone from sight: no need to wait for the spring to finish
                    c.leaving = false
                    removeView(c.view)
                    leavingCards.remove(c)
                    physics.cancel(c.dragM)
                    notifyIfGone()
                }
            }
            m.onEnd = {
                removeView(c.view)
                leavingCards.remove(c)
                notifyIfGone()
            }
            c.dragM = m
            physics.start(m)
        }
        if (delayMs <= 0L) begin.run() else postDelayed(begin, delayMs)
    }

    private fun notifyIfGone() {
        if (!goneNotified && cards.none { !it.isClear } && leavingCards.isEmpty()) {
            goneNotified = true
            onAllGone?.invoke()
        }
    }

    /** Sends every card flying up, nearest to the centre first. */
    fun clearAll() {
        if (busy) return
        busy = true
        val victims = cards.filter { !it.isClear }.sortedBy { abs(it.slot - p) }
        if (victims.isEmpty()) {
            notifyIfGone()
            return
        }
        val tasks = victims.mapNotNull { it.task }
        removeCards(victims, victims.map { -1800f }, 45L)
        onDismissed?.invoke(tasks)
        // The pill fades out while its cards leave.
        cards.firstOrNull { it.isClear }?.let { pill ->
            physics.start(SpringMotion(pill.ea, 0f, 0f, 12f * omegaScale, 1f, 0.002f, 0.02f) { pill.ea = it })
        }
        postDelayed({ notifyIfGone() }, 1500) // safety net
    }

    // ================================================================ big transitions

    private fun spring(
        from: Float, to: Float, omega: Float, zeta: Float,
        restDelta: Float, restSpeed: Float, update: (Float) -> Unit
    ): SpringMotion {
        val m = SpringMotion(from, 0f, to, omega * omegaScale, zeta, restDelta, restSpeed, update)
        physics.start(m)
        return m
    }

    fun playEnter(style: EnterStyle) {
        busy = false
        lastFocus = p.roundToInt()
        val order = cards.sortedBy { abs(it.slot - p) }
        val w = max(width, sw()).toFloat()
        for ((rank, c) in order.withIndex()) {
            c.ex = 0f; c.ey = 0f; c.es = 1f; c.ea = 1f
            when (style) {
                EnterStyle.RISE -> { c.ey = dp(56f); c.es = 1.1f; c.ea = 0f }
                EnterStyle.SLIDE_LEFT -> c.ex = -(w * 1.05f + rank * dp(22f))
                EnterStyle.SLIDE_RIGHT -> c.ex = w * 1.05f + rank * dp(22f)
                EnterStyle.ZOOM -> { c.es = 0.82f; c.ea = 0f }
            }
        }
        applyPositions()

        val z = zeta
        for ((rank, c) in order.withIndex()) {
            val begin = Runnable {
                when (style) {
                    EnterStyle.RISE -> {
                        spring(c.ey, 0f, 11f, max(z, 0.6f), 0.3f, 4f) { c.ey = it }
                        spring(c.es, 1f, 11f, max(z, 0.6f), 0.0008f, 0.01f) { c.es = it }
                        spring(c.ea, 1f, 14f, 1f, 0.002f, 0.02f) { c.ea = it }
                    }
                    EnterStyle.SLIDE_LEFT, EnterStyle.SLIDE_RIGHT -> {
                        spring(c.ex, 0f, 8.5f, max(z, 0.7f), 0.3f, 4f) { c.ex = it }
                    }
                    EnterStyle.ZOOM -> {
                        spring(c.es, 1f, 12f, max(z, 0.6f), 0.0008f, 0.01f) { c.es = it }
                        spring(c.ea, 1f, 14f, 1f, 0.002f, 0.02f) { c.ea = it }
                    }
                }
            }
            val delay = min(rank, 7) * 30L
            if (delay == 0L) begin.run() else postDelayed(begin, delay)
        }
    }

    /**
     * The cards slide back out to the left (the reverse of the default entrance). Every card
     * travels as far as it needs to leave the screen, wherever it is when the exit starts.
     */
    fun playExit(onDone: () -> Unit) {
        if (busy && exitStarted) return
        busy = true
        exitStarted = true
        val order = cards.sortedBy { abs(it.slot - p) }
        val w = max(sw(), 1).toFloat()
        var remaining = order.size
        if (remaining == 0) {
            onDone()
            return
        }
        // offscreen when the card's centre is further left than this (from the screen centre)
        val gone = -(w / 2f + cw / 2f + dp(8f))
        for ((rank, c) in order.withIndex()) {
            place(c.slot - posOf(c))
            val base = outX + c.flipX // where the card sits without the entry offset
            val target = gone - rank * dp(18f) - base
            var counted = false
            val begin = Runnable {
                val m = SpringMotion(c.ex, 0f, target, 9f * omegaScale, 1f, 2f, 40f) { x ->
                    c.ex = x
                    if (!counted && base + x < gone + dp(6f)) {
                        counted = true
                        if (--remaining == 0) onDone()
                    }
                }
                physics.start(m)
            }
            val delay = min(rank, 7) * 24L
            if (delay == 0L) begin.run() else postDelayed(begin, delay)
        }
        postDelayed({ if (remaining > 0) { remaining = 0; onDone() } }, 1300) // safety net
    }

    private var exitStarted = false

    /**
     * The tapped card grows until its thumbnail fills the screen (used when the overlay window is
     * not available). [onReached] is called when it is almost there.
     */
    fun openCard(c: Card, onReached: () -> Unit) {
        busy = true
        physics.cancel(c.dragM)
        c.dragY = 0f
        c.lift = 0f
        openS = width.toFloat() / cw
        openY = height / 2f - c.view.top - cardH / 2f - (headerH / 2f) * openS
        openingCard = c
        spring(0f, 1f, 14f, 1f, 0.002f, 0.02f) { fadeOthers = it }
        var fired = false
        spring(0f, 1f, 10f, max(zeta, 0.92f), 0.002f, 0.02f) { t ->
            c.openT = t
            if (!fired && t > 0.96f) {
                fired = true
                onReached()
            }
        }
    }

    /**
     * The card on screen is about to be replaced by the overlay window's copy: fade the other
     * cards, then hide this one a moment later and call [onDone].
     */
    fun overlayHandoff(c: Card, onDone: () -> Unit) {
        busy = true
        physics.cancel(c.dragM)
        openingCard = c
        spring(0f, 1f, 120f, 1f, 0.02f, 0.5f) { fadeOthers = it }
        postDelayed({
            spring(c.ea, 0f, 45f, 1f, 0.01f, 0.1f) { c.ea = it }
        }, 70)
        postDelayed({ onDone() }, 190)
    }

    /** The thumbnail area of a card in screen coordinates and its current scale. */
    fun thumbRect(c: Card): RectF {
        val v = c.view
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val cx = loc[0] + v.left + v.width / 2f + v.translationX
        val cy = loc[1] + v.top + v.height / 2f + v.translationY
        val sw = v.width * v.scaleX
        val sh = v.height * v.scaleY
        val top = cy - sh / 2f + headerH * v.scaleY
        return RectF(cx - sw / 2f, top, cx + sw / 2f, cy + sh / 2f)
    }

    fun cardRadiusPx() = dp(26f)

    fun focusedCard(): Card? {
        if (cards.isEmpty()) return null
        val i = p.roundToInt().coerceIn(0, cards.size - 1)
        val c = cards[i]
        return if (c.isClear) cards.getOrNull(i + 1) else c
    }

    fun taskCount() = cards.count { !it.isClear }

    // ================================================================ peek (bottom trigger hold)

    /**
     * Called while the finger is still down on the trigger. The cards start out of sight at the
     * left, peeking in; [peekMove] then drags them around with elastic lag (cards nearer to the
     * finger move more), and [peekRelease] lets them spring into their places.
     */
    fun peekStart() {
        busy = true
        peeking = true
        peekReleased = false
        peekDx = 0f
        peekDy = 0f
        val w = max(width, sw()).toFloat()
        peekBase = -(w / 2f + cw / 2f) + dp(72f)
        for (c in cards) {
            c.ex = peekBase - dp(220f)
            c.ey = 0f
            c.es = 1f
            c.ea = 1f
            c.exv = 0f
            c.eyv = 0f
            c.esv = 0f
        }
        applyPositions()
        physics.start(peekMotion)
    }

    fun peekMove(fx: Float, fy: Float, dx: Float, dy: Float) {
        peekFx = fx
        peekFy = fy
        peekDx = dx
        peekDy = dy
    }

    fun peekRelease() {
        if (!peeking) return
        peekReleased = true
        busy = false
        physics.start(peekMotion)
    }

    private val peekMotion = object : Motion() {
        override fun step(dt: Float): Boolean {
            if (!peeking) return false
            val w = max(width, sw()).toFloat()
            val h = sh().toFloat()
            val n = max(1, ceil(dt / SUBSTEP).toInt())
            val hh = dt / n
            var moving = !peekReleased
            val up = (-peekDy / (h * 0.35f)).coerceIn(0f, 1f)
            for (c in cards) {
                var tx = 0f
                var ty = 0f
                var ts = 1f
                var omega = 11f * omegaScale
                var zt = (zeta * 0.75f).coerceIn(0.4f, 0.9f)
                if (!peekReleased) {
                    place(c.slot - posOf(c))
                    val cx = w / 2f + outX + c.ex
                    // 1 right under the finger, falling off with distance
                    val k = 1f / (1f + abs(cx - peekFx) / (cw * 0.9f))
                    tx = peekBase + peekDx.coerceIn(-w, w) * (0.25f + 0.75f * k)
                    ty = 0.55f * peekDy * (0.3f + 0.7f * k)
                    ts = 1f - 0.2f * up * (0.4f + 0.6f * k)
                    omega = (10f + 22f * k) * omegaScale
                    zt = 0.7f
                }
                for (i in 0 until n) {
                    c.exv += (-omega * omega * (c.ex - tx) - 2f * zt * omega * c.exv) * hh
                    c.ex += c.exv * hh
                    c.eyv += (-omega * omega * (c.ey - ty) - 2f * zt * omega * c.eyv) * hh
                    c.ey += c.eyv * hh
                    c.esv += (-omega * omega * (c.es - ts) - 2f * zt * omega * c.esv) * hh
                    c.es += c.esv * hh
                }
                if (peekReleased &&
                    (abs(c.ex) > 0.4f || abs(c.ey) > 0.4f || abs(c.es - 1f) > 0.002f ||
                        abs(c.exv) > 6f || abs(c.eyv) > 6f || abs(c.esv) > 0.05f)
                ) moving = true
            }
            if (!moving) {
                for (c in cards) {
                    c.ex = 0f; c.ey = 0f; c.es = 1f
                    c.exv = 0f; c.eyv = 0f; c.esv = 0f
                }
                peeking = false
            }
            return moving
        }
    }
}
