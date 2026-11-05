package com.example.rootrecents

import android.content.Context
import android.os.Build

/** Material You (dynamic colour) tones from the system palette, with plain fallbacks. */
object MaterialYou {
    private fun night(ctx: Context) =
        (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun c(ctx: Context, id: Int, fallback: Int): Int =
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                ctx.getColor(id)
            } catch (e: Exception) {
                fallback
            }
        } else fallback

    /** Light tonal container (pill, menu). */
    fun container(ctx: Context) =
        if (night(ctx)) {
            if (Build.VERSION.SDK_INT >= 31) c(ctx, android.R.color.system_accent1_800, 0xFF4A4458.toInt())
            else 0xFF4A4458.toInt()
        } else {
            if (Build.VERSION.SDK_INT >= 31) c(ctx, android.R.color.system_accent1_100, 0xFFE8DEF8.toInt())
            else 0xFFE8DEF8.toInt()
        }

    /** Slightly lighter surface for the menu. */
    fun surface(ctx: Context) =
        if (night(ctx)) {
            if (Build.VERSION.SDK_INT >= 31) c(ctx, android.R.color.system_accent1_900, 0xFF2B2930.toInt())
            else 0xFF2B2930.toInt()
        } else {
            if (Build.VERSION.SDK_INT >= 31) c(ctx, android.R.color.system_accent1_50, 0xFFF7F2FA.toInt())
            else 0xFFF7F2FA.toInt()
        }

    /** Text / icon colour on top of [container] and [surface]. */
    fun onContainer(ctx: Context) =
        if (night(ctx)) {
            if (Build.VERSION.SDK_INT >= 31) c(ctx, android.R.color.system_accent1_100, 0xFFE8DEF8.toInt())
            else 0xFFE8DEF8.toInt()
        } else {
            if (Build.VERSION.SDK_INT >= 31) c(ctx, android.R.color.system_accent1_900, 0xFF1D192B.toInt())
            else 0xFF1D192B.toInt()
        }

    /** Accent used for the little icon discs in the menu. */
    fun accent(ctx: Context) =
        if (night(ctx)) {
            if (Build.VERSION.SDK_INT >= 31) c(ctx, android.R.color.system_accent1_200, 0xFFD0BCFF.toInt())
            else 0xFFD0BCFF.toInt()
        } else {
            if (Build.VERSION.SDK_INT >= 31) c(ctx, android.R.color.system_accent1_600, 0xFF6750A4.toInt())
            else 0xFF6750A4.toInt()
        }

    fun onAccent(ctx: Context) =
        if (night(ctx)) {
            if (Build.VERSION.SDK_INT >= 31) c(ctx, android.R.color.system_accent1_900, 0xFF381E72.toInt())
            else 0xFF381E72.toInt()
        } else {
            if (Build.VERSION.SDK_INT >= 31) c(ctx, android.R.color.system_accent1_0, 0xFFFFFFFF.toInt())
            else 0xFFFFFFFF.toInt()
        }
}
