package com.example.rootrecents

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.exp

/** User-tweakable settings, edited in [SettingsActivity]. */
class Config private constructor(private val sp: SharedPreferences) {

    /** A slider setting: stored key, default, and allowed range. */
    class IntSpec(val key: String, val def: Int, val lo: Int, val hi: Int)

    companion object {
        fun get(ctx: Context) =
            Config(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE))

        // ---- look ----
        val CARD_SIZE = IntSpec("cardSize", 60, 45, 75)
        val BLUR = IntSpec("blur", 45, 0, 100)
        val DIM = IntSpec("dim", 25, 0, 80)

        // ---- scrolling ----
        /** Higher = momentum dies sooner (less drifty). */
        val SCROLL_FRICTION = IntSpec("scrollFriction", 45, 1, 100)
        /** How strongly a fling is carried (percent of the finger's release speed). */
        val SCROLL_SPEED = IntSpec("scrollSpeed", 100, 40, 200)
        /** How far the content moves per finger movement (percent). */
        val DRAG_SENS = IntSpec("dragSens", 100, 60, 160)

        // ---- animation feel ----
        val ANIM_SPEED = IntSpec("animSpeed", 100, 50, 200)
        val BOUNCE = IntSpec("bounce", 40, 0, 100)

        // ---- stack mode ----
        val STACK_SCALE = IntSpec("stackScale", 30, 0, 100)
        val STACK_SPREAD = IntSpec("stackSpread", 50, 0, 100)
        val STACK_ELASTIC = IntSpec("stackElastic", 50, 0, 100)

        // ---- neighbours lifting while a card is swiped up ----
        val LIFT_INTENSITY = IntSpec("liftIntensity", 35, 0, 100)
        val LIFT_REACH = IntSpec("liftReach", 50, 0, 100)
        val LIFT_BOUNCE = IntSpec("liftBounce", 40, 0, 100)
    }

    fun int(s: IntSpec): Int = sp.getInt(s.key, s.def).coerceIn(s.lo, s.hi)
    fun setInt(s: IntSpec, v: Int) = sp.edit().putInt(s.key, v.coerceIn(s.lo, s.hi)).apply()

    /** Back to factory settings. */
    fun resetAll() = sp.edit().clear().apply()

    // ---- non-slider settings ----

    /** 0 = normal row of cards, 1 = iOS-style stack */
    var layoutMode: Int
        get() = sp.getInt("layoutMode", 0)
        set(v) = sp.edit().putInt("layoutMode", v).apply()

    /** Normal mode only: settle on a card after scrolling. The stack never snaps. */
    var snap: Boolean
        get() = sp.getBoolean("snap", true)
        set(v) = sp.edit().putBoolean("snap", v).apply()

    /** 0 rise, 1 slide from left, 2 slide from right, 3 zoom */
    var enterStyle: Int
        get() = sp.getInt("enterStyle", 1)
        set(v) = sp.edit().putInt("enterStyle", v).apply()

    var showForeground: Boolean
        get() = sp.getBoolean("showForeground", false)
        set(v) = sp.edit().putBoolean("showForeground", v).apply()

    /**
     * Only when the foreground app is shown: put the launcher behind the switcher (like iOS, where
     * the home screen is behind the cards) and give the foreground card a fresh screenshot.
     */
    var launcherBehind: Boolean
        get() = sp.getBoolean("launcherBehind", false)
        set(v) = sp.edit().putBoolean("launcherBehind", v).apply()

    var showLabels: Boolean
        get() = sp.getBoolean("showLabels", true)
        set(v) = sp.edit().putBoolean("showLabels", v).apply()

    /** Master switch for every haptic. */
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    /** A tick every time a card comes into focus (cards mode). */
    var focusTick: Boolean
        get() = sp.getBoolean("focusTick", true)
        set(v) = sp.edit().putBoolean("focusTick", v).apply()

    /** Also force-stop apps when they are dismissed. Off = just close them like normal recents. */
    var forceStop: Boolean
        get() = sp.getBoolean("forceStop", false)
        set(v) = sp.edit().putBoolean("forceStop", v).apply()

    /** Comma separated package names that are never force-stopped (only matters if [forceStop]). */
    var neverStop: String
        get() = sp.getString("neverStop", "com.arlosoft.macrodroid,com.topjohnwu.magisk,com.termux,deltazero.amarok")
            ?: ""
        set(v) = sp.edit().putString("neverStop", v).apply()

    val neverStopSet: Set<String>
        get() = neverStop.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    // ---- derived values used by the physics ----

    val blur: Int get() = int(BLUR)
    val cardSize: Int get() = int(CARD_SIZE)

    /** Blur radius in pixels for window-level blur-behind. */
    val blurRadiusPx: Float get() = blur * 1.6f

    val dimAlpha: Float get() = int(DIM) / 100f

    /** Momentum decay per second (velocity *= e^-decay each second). */
    val decay: Float get() = 1.5f + int(SCROLL_FRICTION) * 0.12f

    val velocityScale: Float get() = int(SCROLL_SPEED) / 100f
    val dragScale: Float get() = int(DRAG_SENS) / 100f

    /** Multiplies every spring's natural frequency: >1 = quicker animations. */
    val omegaScale: Float get() = int(ANIM_SPEED) / 100f

    /** Damping ratio for general springs: 1 = no overshoot, lower = more bounce. */
    val zeta: Float get() = (1.02f - int(BOUNCE) * 0.0046f).coerceAtMost(1f)

    /** Per-card scale falloff behind the focused card in stack mode (kept subtle). */
    val stackScaleK: Float get() = int(STACK_SCALE) * 0.001f
    val stackSpread: Float get() = int(STACK_SPREAD) / 100f
    val stackElastic: Float get() = int(STACK_ELASTIC) / 100f

    /** How much of the drag the nearest neighbours lift (further ones follow the chain). */
    val liftIntensity: Float get() = int(LIFT_INTENSITY) / 100f * 0.8f

    /** Fraction of the lift passed on to each next-further card. */
    val liftPassOn: Float get() = exp(-(2.0f - 0.018f * int(LIFT_REACH)))

    /** Damping ratio when neighbours fall back after an un-cleared swipe (lower = more bounce). */
    val liftBounceZeta: Float get() = (0.85f - 0.0065f * int(LIFT_BOUNCE)).coerceAtLeast(0.15f)
}
