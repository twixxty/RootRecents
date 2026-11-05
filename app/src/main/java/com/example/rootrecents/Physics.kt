package com.example.rootrecents

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max

/**
 * A tiny physics engine: every moving thing is a [Motion] stepped once per display frame with the
 * real frame time. Nothing is a timed tween. Velocity is always carried over from the gesture that
 * started the motion, so hand-offs between "finger" and "physics" have no jump.
 */
abstract class Motion {
    var onEnd: (() -> Unit)? = null

    /** Advance by [dt] seconds. Return true while still moving. */
    abstract fun step(dt: Float): Boolean
}

class Physics {

    private val active = ArrayList<Motion>()
    private val tmp = ArrayList<Motion>()
    private val choreographer = Choreographer.getInstance()
    private var last = 0L
    private var posted = false

    /** Called after every frame in which something moved (apply positions here). */
    var onFrameEnd: (() -> Unit)? = null

    private val callback = Choreographer.FrameCallback { now -> frame(now) }

    fun start(m: Motion): Motion {
        if (!active.contains(m)) active.add(m)
        if (!posted) {
            posted = true
            last = 0L
            choreographer.postFrameCallback(callback)
        }
        return m
    }

    fun cancel(m: Motion?) {
        if (m != null) active.remove(m)
    }

    fun cancelAll() {
        active.clear()
    }

    private fun frame(now: Long) {
        posted = false
        val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0.001f, 0.034f)
        last = now

        tmp.clear()
        tmp.addAll(active)
        for (m in tmp) {
            if (!active.contains(m)) continue // cancelled by an earlier motion this frame
            if (!m.step(dt)) {
                active.remove(m)
                m.onEnd?.invoke()
            }
        }
        onFrameEnd?.invoke()

        if (active.isNotEmpty()) {
            posted = true
            choreographer.postFrameCallback(callback)
        } else {
            last = 0L
        }
    }
}

private const val SUBSTEP = 1f / 240f

/** Damped spring towards [target]. omega = natural frequency (rad/s), zeta = damping ratio. */
class SpringMotion(
    var value: Float,
    var velocity: Float,
    var target: Float,
    private val omega: Float,
    private val zeta: Float,
    private val restDelta: Float,
    private val restSpeed: Float,
    private val onUpdate: (Float) -> Unit
) : Motion() {

    override fun step(dt: Float): Boolean {
        val n = max(1, ceil(dt / SUBSTEP).toInt())
        val h = dt / n
        for (i in 0 until n) {
            val a = -omega * omega * (value - target) - 2f * zeta * omega * velocity
            velocity += a * h
            value += velocity * h
        }
        if (abs(value - target) < restDelta && abs(velocity) < restSpeed) {
            value = target
            velocity = 0f
            onUpdate(value)
            return false
        }
        onUpdate(value)
        return true
    }
}

/**
 * Momentum scrolling: exponential velocity decay inside [lo, hi] (like a scroll view), and a
 * critically damped spring pulling back when the position is outside the range (rubber band).
 */
class ScrollMotion(
    var value: Float,
    var velocity: Float,
    private val decayPerSecond: Float,
    private val lo: Float,
    private val hi: Float,
    private val edgeOmega: Float,
    private val restSpeed: Float,
    /** Extra constant deceleration (units/s^2): makes the tail stop crisply like a real scroller. */
    private val linearDecel: Float = 0f,
    private val onUpdate: (Float) -> Unit
) : Motion() {

    override fun step(dt: Float): Boolean {
        val n = max(1, ceil(dt / SUBSTEP).toInt())
        val h = dt / n
        val keep = exp(-decayPerSecond * h)
        for (i in 0 until n) {
            if (value < lo || value > hi) {
                val tgt = if (value < lo) lo else hi
                val a = -edgeOmega * edgeOmega * (value - tgt) - 2f * edgeOmega * velocity
                velocity += a * h
                value += velocity * h
            } else {
                val before = value
                velocity *= keep
                if (linearDecel > 0f) {
                    val dv = linearDecel * h
                    velocity = if (velocity > dv) velocity - dv else if (velocity < -dv) velocity + dv else 0f
                }
                value += velocity * h
                // Crossing an edge with speed: lose most of it, like an overscrolling list.
                if ((before in lo..hi) && (value < lo || value > hi)) velocity *= 0.35f
            }
        }
        onUpdate(value)
        val inside = value in lo..hi
        if (inside && abs(velocity) < restSpeed) return false
        if (!inside) {
            val tgt = if (value < lo) lo else hi
            if (abs(value - tgt) < 0.0005f && abs(velocity) < restSpeed) {
                value = tgt
                velocity = 0f
                onUpdate(value)
                return false
            }
        }
        return true
    }
}
