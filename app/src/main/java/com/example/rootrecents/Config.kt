package com.example.rootrecents

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlin.math.exp

/** User-tweakable settings, edited in [SettingsActivity]. */
class Config private constructor(private val sp: SharedPreferences) {

    /** A slider setting: stored key, default, and allowed range. */
    class IntSpec(val key: String, val def: Int, val lo: Int, val hi: Int)

    companion object {
        fun get(ctx: Context) =
            Config(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE))

        // ---- look ----
        val CARD_SIZE = IntSpec("cardSize2", 66, 45, 80)
        val BLUR = IntSpec("blur", 45, 0, 100)
        val TINT = IntSpec("tint", 30, 0, 80)

        // ---- the few "feel" sliders that drive everything ----
        /** Higher = momentum dies sooner. */
        val SCROLL_FRICTION = IntSpec("scrollFriction2", 35, 1, 100)
        val ANIM_SPEED = IntSpec("animSpeed", 100, 50, 200)
        val BOUNCE = IntSpec("bounce", 40, 0, 100)
        /** Stack deck elasticity. */
        val ELASTIC = IntSpec("stackElastic", 50, 0, 100)
        val STACK_SPREAD = IntSpec("stackSpread", 50, 0, 100)
        /** How much the neighbours of a swiped-up card lift. */
        val LIFT = IntSpec("lift5", 5, 0, 100)
        /** Gravity pulling the cards back down after swiping up to close. */
        val PULL_GRAVITY = IntSpec("pullGravity", 50, 0, 100)

        // ---- opening animation / screen shape ----
        val RADIUS_DP = IntSpec("radiusDp", 28, 0, 70)

        // ---- card -> app transition ----
        /** Speed of the card growing to fill the screen (percent). */
        val OPEN_SPEED = IntSpec("openSpeed", 100, 40, 220)
        /** Extra time (ms) the full-screen cover stays after the app has started. */
        val OPEN_HOLD = IntSpec("openHold", 150, 0, 1000)
        /** How long (ms) the cover takes to fade away. */
        val OPEN_FADE = IntSpec("openFade", 250, 40, 1000)

        // ---- bottom trigger ----
        /** Height of the touch zone along the bottom edge, in dp (bigger = easier to hit). */
        val TRIG_ZONE = IntSpec("trigZone", 24, 8, 80)
        /** How far (dp) you swipe up before it counts. */
        val TRIG_LENGTH = IntSpec("trigLength", 70, 20, 250)
        /** How long (ms) you hold still after swiping up for the peek to start. */
        val TRIG_HOLD = IntSpec("trigHold", 350, 100, 1200)
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
        get() = sp.getBoolean("snap", false)
        set(v) = sp.edit().putBoolean("snap", v).apply()

    /** 0 rise, 1 slide from left, 2 slide from right, 3 zoom */
    var enterStyle: Int
        get() = sp.getInt("enterStyle", 1)
        set(v) = sp.edit().putInt("enterStyle", v).apply()

    var showForeground: Boolean
        get() = sp.getBoolean("showForeground", false)
        set(v) = sp.edit().putBoolean("showForeground", v).apply()

    var showLabels: Boolean
        get() = sp.getBoolean("showLabels", true)
        set(v) = sp.edit().putBoolean("showLabels", v).apply()

    /** 0 = Screenshot button, 1 = Clear all button, 2 = nothing, below the cards. */
    var bottomButton: Int
        get() = sp.getInt("bottomButton", 0)
        set(v) = sp.edit().putInt("bottomButton", v).apply()

    /** The "Clear all" card at the left end of the row. */
    var clearLeft: Boolean
        get() = sp.getBoolean("clearLeft", true)
        set(v) = sp.edit().putBoolean("clearLeft", v).apply()

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

    // ---- tint / open animation ----

    /** 0 = Material You accent, 1 = custom colour. */
    var tintMode: Int
        get() = sp.getInt("tintMode", 0)
        set(v) = sp.edit().putInt("tintMode", v).apply()

    var tintCustom: Int
        get() = sp.getInt("tintCustom", 0xFF1B2A41.toInt())
        set(v) = sp.edit().putInt("tintCustom", v).apply()

    /** What happens to the card as it fills the screen: 0 blur, 1 colour wash, 2 nothing. */
    var openEffect: Int
        get() = sp.getInt("openEffect", 0)
        set(v) = sp.edit().putInt("openEffect", v).apply()

    /** Detect the screen's corner radius automatically (Android 12+), else use [RADIUS_DP]. */
    var radiusAuto: Boolean
        get() = sp.getBoolean("radiusAuto", true)
        set(v) = sp.edit().putBoolean("radiusAuto", v).apply()

    // ---- bottom trigger ----

    var triggerEnabled: Boolean
        get() = sp.getBoolean("triggerEnabled", false)
        set(v) = sp.edit().putBoolean("triggerEnabled", v).apply()

    /** Swiping up and holding shows the peek; off = a swipe up just opens recents. */
    var triggerHold: Boolean
        get() = sp.getBoolean("triggerHoldOn", true)
        set(v) = sp.edit().putBoolean("triggerHoldOn", v).apply()

    /** Read touches with root instead of an overlay strip (needed on ColorOS' gesture handle). */
    var rawTouch: Boolean
        get() = sp.getBoolean("rawTouch", false)
        set(v) = sp.edit().putBoolean("rawTouch", v).apply()

    // ---- derived values used by the physics ----

    val blur: Int get() = int(BLUR)
    val cardSize: Int get() = int(CARD_SIZE)

    /** Blur radius in pixels for window-level blur-behind. */
    val blurRadiusPx: Float get() = blur * 1.6f

    val tintAlpha: Float get() = int(TINT) / 100f

    /** The tint colour (opaque): Material You accent or the user's choice. */
    fun tintColor(ctx: Context): Int {
        if (tintMode == 0 && Build.VERSION.SDK_INT >= 31) {
            try {
                return ctx.getColor(android.R.color.system_accent1_700) or 0xFF000000.toInt()
            } catch (e: Exception) {
                // fall through
            }
        }
        return tintCustom or 0xFF000000.toInt()
    }

    /** Momentum decay per second (velocity *= e^-decay each second). */
    val decay: Float get() = 1.0f + int(SCROLL_FRICTION) * 0.07f

    val velocityScale: Float get() = 1f
    val dragScale: Float get() = 1f

    /** Multiplies every spring's natural frequency: >1 = quicker animations. */
    val omegaScale: Float get() = int(ANIM_SPEED) / 100f

    /** Damping ratio for general springs: 1 = no overshoot, lower = more bounce. */
    val zeta: Float get() = (1.02f - int(BOUNCE) * 0.0046f).coerceAtMost(1f)

    /** Per-card scale falloff behind the focused card in stack mode: kept subtle. */
    val stackScaleK: Float get() = 0.012f
    val stackSpread: Float get() = int(STACK_SPREAD) / 100f
    val stackElastic: Float get() = int(ELASTIC) / 100f

    /** Fraction of the drag the nearest neighbours lift (further ones follow the chain). */
    val liftIntensity: Float get() = int(LIFT) / 100f * 0.8f

    /** Fraction of the lift passed on to each next-further card. */
    val liftPassOn: Float get() = exp(-1.1f)

    /** Damping ratio when neighbours fall back after an un-cleared swipe (lower = more bounce). */
    val liftBounceZeta: Float get() = (0.75f - 0.0055f * int(BOUNCE)).coerceAtLeast(0.15f)

    /** How quickly the cards settle back down after the bottom swipe-up lift (spring frequency). */
    val pullGravity: Float get() = 6f + 0.14f * int(PULL_GRAVITY)

    /** How strongly the bounce (kick) is felt, 0..1. */
    val bounceAmount: Float get() = 0.3f + int(BOUNCE) / 100f * 0.9f
}
