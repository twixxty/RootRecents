package com.example.rootrecents

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the bottom trigger back after a reboot if it was switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Config.get(ctx).triggerEnabled && android.provider.Settings.canDrawOverlays(ctx)) {
            TriggerService.start(ctx)
        }
    }
}
