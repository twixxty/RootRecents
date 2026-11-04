package com.example.rootrecents

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
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
 * The app switcher itself. Instead of a RecyclerView + snap helper (which animates with fixed
 * timings and causes a hitch when a fling hands over to the snap), every card is placed from one
 * continuous scroll position [p] and every movement is a physics [Motion].
 *
 *  - NORMAL mode: a flat row of cards, nothing scaled, optional snapping.
 *  - STACK mode: iOS-style deck. Newer cards (to the right) sit in front of the focused card, older
 *    ones (to the left) are tucked behind it. Stacking order never changes. Never snaps, and each
 *    card follows the scroll on its own spring so quick flicks stretch the deck elastically.
 *
 * In both modes, dragging a card upward lifts its neighbours (nearest first, each one following
 * the one before it), and they settle back down when the card is released.
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

        var slot = 0
        var dragY = 0f
        var lift = 0f
        var liftV = 0f
        var q = 0f // stack mode: this card's own, springy view of the scroll position
        var qv = 0f
        var flipX = 0f
        var flipS = 1f
        var ex = 0f
        var ey = 0f
        var es = 1f
        var ea = 1f
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
    var snap = true
    var haptics = true
    var focusTick = true
    var showLabels = true
    var cardPercent = 60
    private var decay = 6.9f
    private var velScale = 1f
    private var dragScale = 1f
    private var omegaScale = 1f
    private var zeta = 0.84f
    private var stackScaleK = 0.03f
    private var stackSpread = 0.5f
    private var stackElastic = 0.5f
    private var liftI = 0.28f
    private var liftPassOn = 0.33f
    private var liftBounceZeta = 0.6f

    fun applyConfig(c: Config) {
        mode = if (c.layoutMode == 1) Mode.STACK else Mode.NORMAL
        snap = c.snap
        haptics = c.haptics
        focusTick = c.focusTick
        showLabels = c.showLabels
        cardPercent = c.cardSize
        decay = c.decay
        velScale = c.velocityScale
        dragScale = c.dragScale
        omegaScale = c.omegaScale
        zeta = c.zeta
        stackScaleK = c.stackScaleK
        stackSpread = c.stackSpread
        stackElastic = c.stackElastic
        liftI = c.liftIntensity
        liftPassOn = c.liftPassOn
        liftBounceZeta = c.liftBounceZeta
    }

    // ---- callbacks ----
    var onCardClick: ((Card) -> Unit)? = null
    var onCardLongPress: ((RecentTask) -> Unit)? = null
    var onClearClick: (() -> Unit)? = null
    var onEmptyTap: (() -> Unit)? = null
    var onDismissed: ((List<RecentTask>) -> Unit)? = null
    var onAllGone: (() -> Unit)? = null

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density

    private val physics = Physics()
    private val cards = ArrayList<Card>()
    private val leavingCards = ArrayList<Card>()
    private var p = 0f
    private var scrollM: Motion? = null

    // geometry
    private var cw = 0
    private var cardH = 0
    private var headerH = 0
    private var thumbH = 0
    private var topPad = 0
    private var pitchN = 0f   // normal mode: distance between card centres
    private var pitchS = 0f   // stack mode: how far a card to the right of focus sits per step
    private var leftMax = 0f  // stack mode: how far the deck behind the focused card fans out

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
        private const val SUBSTEP = 1f / 240f
    }

    // ================================================================ setup

    private fun computeGeometry() {
        val w = dm.widthPixels
        val h = dm.heightPixels
        cw = (w * cardPercent / 100f).toInt()
        thumbH = (cw.toLong() * h / w).toInt()
        headerH = dp(40f).toInt()
        cardH = headerH + thumbH
        topPad = (h - cardH) / 2
        pitchN = cw + dp(14f)
        pitchS = cw * (0.70f + 0.30f * stackSpread)
        leftMax = cw * (0.25f + 0.30f * stackSpread)
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

        val inflater = LayoutInflater.from(context)
        addCard(Card(inflater.inflate(R.layout.item_clear, this, false), null))
        for (t in tasks) {
            val c = Card(inflater.inflate(R.layout.item_task, this, false), t)
            bind(c)
            addCard(c)
        }
        cards.forEachIndexed { i, c -> c.slot = i }
        p = (cards.size - 1).toFloat()
        for (c in cards) {
            c.q = p
            c.qv = 0f
        }
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
        val header = c.header!!
        header.layoutParams.height = headerH
        header.visibility = if (showLabels) View.VISIBLE else View.INVISIBLE
        c.label!!.text = t.label
        c.icon!!.setImageDrawable(t.icon)

        val th = c.thumb!!
        th.layoutParams.height = thumbH
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

    /** The scroll position this card is drawn at (stack cards each have their own springy one). */
    private fun posOf(c: Card) = if (mode == Mode.STACK) c.q else p

    /**
     * Where a card [d] steps away from the focus goes (horizontal offset from the screen centre and
     * scale). Fills outX / outS.
     *
     * Stack: cards to the right (newer) step out linearly and overlap the focused card a little,
     * cards to the left (older) tuck behind it and bunch up towards the screen edge.
     */
    private fun place(d: Float) {
        if (mode == Mode.NORMAL) {
            outX = d * pitchN
            outS = 1f
        } else if (d >= 0f) {
            outX = pitchS * d
            outS = 1f
        } else {
            val ad = -d
            val s = max(0.82f, 1f - stackScaleK * ad)
            val leftEdge = -leftMax * (1f - exp(-1.35f * ad))
            outS = s
            outX = leftEdge - (1f - s) * cw / 2f
        }
    }

    private fun applyPositions() {
        for (c in cards) {
            val d = c.slot - posOf(c)
            place(d)
            var x = outX + c.flipX + c.ex
            var s = outS * c.flipS * c.es
            var y = c.dragY + c.ey + c.lift
            var a = c.ea
            val v = c.view

            if (mode == Mode.STACK) {
                val label = c.label
                if (label != null) {
                    // The title stays while a card is at or in front of the focus (to the right),
                    // and fades out only as it is tucked behind to the left.
                    label.alpha = if (d >= 0f) 1f else (1f + d * 1.6f).coerceIn(0f, 1f)
                }
                if (d < 0f) a *= 1f - ((-d) - 4f).coerceIn(0f, 2f) / 2f
            }

            val opening = openingCard
            if (opening != null) {
                if (c === opening) {
                    val t = c.openT
                    x += (0f - x) * t
                    y += (openY - y) * t
                    s += (openS - s) * t
                    c.header?.alpha = (1f - t * 3f).coerceIn(0f, 1f)
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

    /**
     * Stack mode: every card chases the scroll position on its own spring. Cards far from the
     * focus are looser, so a quick flick stretches the deck and it springs back together.
     */
    private val followMotion = object : Motion() {
        override fun step(dt: Float): Boolean {
            val n = max(1, ceil(dt / SUBSTEP).toInt())
            val h = dt / n
            var moving = false
            for (c in cards) {
                val dist = min(abs(c.slot - p), 5f)
                val tau = 0.0125f + stackElastic * (0.012f + 0.035f * dist)
                val omega = min(80f, 1f / tau)
                val zt = 0.85f - 0.2f * stackElastic
                for (i in 0 until n) {
                    val a = omega * omega * (p - c.q) - 2f * zt * omega * c.qv
                    c.qv += a * h
                    c.q += c.qv * h
                }
                if (abs(p - c.q) > 0.0008f || abs(c.qv) > 0.01f) moving = true
            }
            if (!moving) {
                for (c in cards) {
                    c.q = p
                    c.qv = 0f
                }
            }
            return moving
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
                val omega = liftOmega * max(0.5f, 1f - 0.08f * k)
                for (j in 0 until n) {
                    val acc = -omega * omega * (c.lift - target) - 2f * liftZeta * omega * c.liftV
                    c.liftV += acc * h
                    c.lift += c.liftV * h
                }
                if (abs(c.lift - target) > 0.3f || abs(c.liftV) > 3f) moving = true
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

    private fun liftBounceBack() {
        liftZeta = liftBounceZeta // falls back with a bounce
        liftOmega = 26f * omegaScale
        physics.start(liftMotion)
    }

    private fun liftFloatDown() {
        liftSrc = null // the card is gone: neighbours float gently back down
        liftZeta = 1f
        liftOmega = 15f * omegaScale
        physics.start(liftMotion)
    }

    // ================================================================ touch

    private fun pitchF() = (if (mode == Mode.STACK) pitchS else pitchN) / dragScale

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

    /** The "Clear all" pill only reacts near the pill, not on its whole card-sized slot. */
    private fun inPill(c: Card, x: Float, y: Float): Boolean {
        val lx = localX(c, x)
        val ly = localY(c, y)
        return abs(lx - cw / 2f) < dp(90f) && abs(ly - (headerH + thumbH / 2f)) < dp(44f)
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
                hit = hitTest(e.x, e.y)
                hit?.let { physics.cancel(it.dragM) } // grab a card that is still springing back
                hitHeader = hit?.let { it.task != null && localY(it, e.y) <= headerH } ?: false
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
                            gesture = if (hit?.task != null && abs(dy) > abs(dx)) G_CARD else G_SCROLL
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
            c.isClear -> if (inPill(c, x, y)) onClearClick?.invoke() else onEmptyTap?.invoke()
            else -> onCardClick?.invoke(c)
        }
    }

    // ================================================================ scrolling physics

    private fun maxP() = max(0f, (cards.size - 1).toFloat())

    private fun scrollBy(delta: Float) {
        val lo = 0f
        val hi = maxP()
        val np = p + delta
        setP(
            when {
                p in lo..hi -> when {
                    np < lo -> lo + (np - lo) * 0.3f
                    np > hi -> hi + (np - hi) * 0.3f
                    else -> np
                }
                // already past an edge: moving back is free, pulling further is resisted
                p < lo -> if (delta > 0f) p + delta else p + delta * 0.3f
                else -> if (delta < 0f) p + delta else p + delta * 0.3f
            }
        )
        applyPositions()
    }

    private fun releaseScroll(vxPx: Float) {
        val v = -vxPx / pitchF() * velScale // cards per second
        val lo = 0f
        val hi = maxP()
        if (mode == Mode.NORMAL && snap && p in lo..hi) {
            // Where the momentum would carry us, then glide there with the same velocity.
            val target = (p + v / decay).coerceIn(lo, hi).roundToInt().toFloat()
            val x0 = abs(p - target)
            val towards = (target - p) * v > 0f
            val base = 14f * omegaScale
            // Critically damped springs don't overshoot as long as omega >= |v| / distance.
            val omega = if (towards && x0 > 0.02f) max(base, min(80f, abs(v) / x0)) else base
            startScroll(SpringMotion(p, v, target, omega, 1f, 0.002f, 0.02f) { setP(it) })
        } else {
            startScroll(ScrollMotion(p, v, decay, lo, hi, 18f, 0.01f) { setP(it) })
        }
    }

    /** Come to rest on the nearest card (normal mode with snapping), or just stop. */
    private fun settle() {
        if (mode == Mode.NORMAL && snap) {
            val target = p.coerceIn(0f, maxP()).roundToInt().toFloat()
            startScroll(SpringMotion(p, 0f, target, 14f * omegaScale, 1f, 0.002f, 0.02f) { setP(it) })
        } else if (p < 0f || p > maxP()) {
            startScroll(ScrollMotion(p, 0f, decay, 0f, maxP(), 18f, 0.01f) { setP(it) })
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
            // Not far enough: it settles back with a little bounce, and so do the neighbours.
            physics.cancel(c.dragM)
            val m = SpringMotion(c.dragY, vy, 0f, 16f * omegaScale, min(zeta, 0.85f), 0.3f, 4f) {
                c.dragY = it
                physics.start(liftMotion)
            }
            m.onEnd = { if (liftSrc === c) liftSrc = null }
            c.dragM = m
            physics.start(m)
            if (liftSrc === c) liftBounceBack()
        }
    }

    // ================================================================ removing cards

    fun dismissCard(c: Card, vy: Float) {
        val t = c.task ?: return
        if (c.leaving) return
        liftAnchor = c.slot
        liftFloatDown()
        removeCards(listOf(c), listOf(vy), 0L)
        onDismissed?.invoke(listOf(t))
    }

    /**
     * Takes [victims] out of the row. The rest of the cards keep exactly the pixels they were at
     * (FLIP) and then glide to their new places on springs, so the gap closes smoothly instead of
     * the list jumping. Victims fly off the top on their own physics.
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
        for (c in cards) {
            c.q = p
            c.qv = 0f
        }

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
        val mx = SpringMotion(fx, vx, 0f, om, max(zeta, 0.5f), 0.3f, 4f) { c.flipX = it }
        val ms = SpringMotion(fs, vs, 1f, om, max(zeta, 0.5f), 0.0008f, 0.01f) { c.flipS = it }
        c.flipXM = mx
        c.flipSM = ms
        physics.start(mx)
        physics.start(ms)
    }

    private fun startLeave(c: Card, vy: Float, delayMs: Long) {
        physics.cancel(c.dragM)
        val h = dm.heightPixels.toFloat()
        val begin = Runnable {
            val m = SpringMotion(c.dragY, min(vy, -1400f), -h * 1.35f, 7f * omegaScale, 1f, h * 0.3f, 8f) { y ->
                c.dragY = y
                c.view.translationY = y
                c.view.alpha = (1f + y / (h * 0.55f)).coerceIn(0f, 1f)
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
        if (!goneNotified && cards.size <= 1 && leavingCards.isEmpty()) {
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
        cards.firstOrNull()?.let { pill ->
            physics.start(SpringMotion(pill.ea, 0f, 0f, 12f * omegaScale, 1f, 0.002f, 0.02f) { pill.ea = it })
        }
        postDelayed({ notifyIfGone() }, 3000) // safety net
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
        val w = max(width, dm.widthPixels).toFloat()
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

    /** Fades and slightly enlarges everything, then calls [onDone]. */
    fun playExit(onDone: () -> Unit) {
        if (busy && exitT > 0f) return
        busy = true
        var fired = false
        spring(exitT, 1f, 11f, 1f, 0.002f, 0.02f) { t ->
            exitT = t
            if (!fired && t > 0.97f) {
                fired = true
                onDone()
            }
        }
    }

    /**
     * The tapped card grows until its thumbnail fills the screen. [onReached] is called when it
     * is almost there; everything else fades away on the way.
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

    fun taskCount() = cards.count { !it.isClear }
}
