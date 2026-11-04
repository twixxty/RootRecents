package com.example.rootrecents

import android.content.Context
import android.content.SharedPreferences

/** User-tweakable settings, edited in [SettingsActivity]. */
class Config private constructor(private val sp: SharedPreferences) {

    companion object {
        fun get(ctx: Context) =
            Config(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE))
    }

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

    /** Background blur strength, 0..100 */
    var blur: Int
        get() = sp.getInt("blur", 45)
        set(v) = sp.edit().putInt("blur", v).apply()

    /** Darkening on top of the blur, 0..80 (percent) */
    var dim: Int
        get() = sp.getInt("dim", 25)
        set(v) = sp.edit().putInt("dim", v).apply()

    /** Card width as a percentage of screen width */
    var cardSize: Int
        get() = sp.getInt("cardSize", 60)
        set(v) = sp.edit().putInt("cardSize", v).apply()

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

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

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

    /** Blur radius in pixels for window-level blur-behind. */
    val blurRadiusPx: Float get() = blur * 1.6f

    val dimAlpha: Float get() = dim / 100f
}
