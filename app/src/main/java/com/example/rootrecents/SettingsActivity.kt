package com.example.rootrecents

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Opened from the long-press shortcut on the app icon. Everything is stored immediately. */
class SettingsActivity : Activity() {

    private lateinit var cfg: Config
    private lateinit var col: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Root Recents"
        cfg = Config.get(this)

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        section("Layout")
        choice("Style", listOf("Cards (flat row, Android 16 pill)", "Stack (iOS)"), cfg.layoutMode) { cfg.layoutMode = it }
        toggle("Snap to cards (cards style only; the stack never snaps)", cfg.snap) { cfg.snap = it }
        choice(
            "Opening animation",
            listOf("Rise", "Slide in from the left", "Slide in from the right", "Zoom"),
            cfg.enterStyle
        ) { cfg.enterStyle = it }
        slider("Card size", Config.CARD_SIZE) { "$it%" }
        toggle("Show app icon and name", cfg.showLabels) { cfg.showLabels = it }
        choice("Button under the cards", listOf("Screenshot", "Clear all", "Nothing"), cfg.bottomButton) { cfg.bottomButton = it }
        toggle("\"Clear all\" at the left end of the cards", cfg.clearLeft) { cfg.clearLeft = it }

        section("Feel")
        slider("Scroll friction (higher = stops sooner)", Config.SCROLL_FRICTION) { "$it" }
        slider("Animation speed", Config.ANIM_SPEED) { "$it%" }
        slider("Bounciness", Config.BOUNCE) { "$it%" }
        slider("Stack elasticity", Config.ELASTIC) { "$it%" }
        slider("Stack spread", Config.STACK_SPREAD) { "$it%" }
        slider("Neighbour lift when swiping a card up", Config.LIFT) { "$it%" }
        toggle("Haptic feedback", cfg.haptics) { cfg.haptics = it }
        toggle("Tick when a card comes into focus (cards style)", cfg.focusTick) { cfg.focusTick = it }

        section("Background")
        slider("Blur", Config.BLUR) { "$it%" }
        slider("Tint strength", Config.TINT) { "$it%" }
        colorRow()

        section("When opening an app")
        choice("Effect", listOf("Blur", "Colour wash", "None"), cfg.openEffect) { cfg.openEffect = it }
        toggle("Detect the screen's corner radius automatically", cfg.radiusAuto) { cfg.radiusAuto = it }
        slider("Screen corner radius (used if not detected)", Config.RADIUS_DP, showPreview = true) { "$it dp" }
        note("While you drag the radius slider the screen's edge is outlined so you can match it.")

        section("Foreground app")
        toggle("Show the app that is in the foreground", cfg.showForeground) { cfg.showForeground = it }

        section("Bottom trigger")
        toggle("Enable the bottom swipe trigger", cfg.triggerEnabled) { setTrigger(it) }
        toggle("Show the peek animation while holding", cfg.triggerHold) { cfg.triggerHold = it }
        slider("Sensitivity (height of the touch zone)", Config.TRIG_ZONE) { "$it dp" }
        slider("Swipe length", Config.TRIG_LENGTH) { "$it dp" }
        slider("Hold time", Config.TRIG_HOLD) { "$it ms" }
        note(
            "A thin strip along the bottom edge. Swipe up and let go: home screen. Swipe up and hold: " +
                "recents. With the peek on, the screen blurs and the cards peek in and follow your " +
                "finger until you let go. If your system gesture bar swallows the swipe, make the " +
                "zone taller."
        )

        section("Dismissing")
        toggle("Also force-stop apps when dismissed", cfg.forceStop) { cfg.forceStop = it }
        textRow(
            "Never force-stop these packages",
            "Comma separated package names. Only used when force-stop is on."
        )

        section("Tools")
        button("Open recents now") {
            startActivity(Intent(this, RecentsActivity::class.java))
        }
        button("Diagnostics (copy report)") { runDiagnostics() }
        button("Reset all settings") {
            AlertDialog.Builder(this)
                .setTitle("Reset all settings?")
                .setMessage("Every option goes back to its default.")
                .setPositiveButton("Reset") { _, _ ->
                    TriggerService.stop(this)
                    cfg.resetAll()
                    recreate()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        note(
            "Open from a root shell with:\nam start -a com.example.rootrecents.SHOW\n\n" +
                "Settings are read each time recents opens."
        )
    }

    override fun onPause() {
        super.onPause()
        // Rebuild the trigger strip so new size / length / hold values apply.
        if (cfg.triggerEnabled && android.provider.Settings.canDrawOverlays(this)) TriggerService.start(this)
    }

    private fun setTrigger(on: Boolean) {
        if (!on) {
            cfg.triggerEnabled = false
            TriggerService.stop(this)
            return
        }
        val app = applicationContext
        Thread {
            Actions.ensureOverlayPermission(app)
            Actions.grantNotifications(app)
            runOnUiThread {
                if (android.provider.Settings.canDrawOverlays(this)) {
                    cfg.triggerEnabled = true
                    TriggerService.start(this)
                } else {
                    Toast.makeText(
                        this, "Allow \"Display over other apps\" for Root Recents first", Toast.LENGTH_LONG
                    ).show()
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- colour

    private var swatch: TextView? = null

    private fun colorRow() {
        val title = TextView(this).apply {
            text = "Tint colour"
            textSize = 16f
        }
        val sw = TextView(this).apply {
            textSize = 14f
            alpha = 0.9f
        }
        swatch = sw
        refreshSwatch()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
            setOnClickListener { pickColor() }
        }
        col.addView(row, matchWrap())
    }

    private fun refreshSwatch() {
        val c = cfg.tintColor(this)
        swatch?.text = (if (cfg.tintMode == 0) "Material You  " else "Custom  ") + "\u25CF"
        swatch?.setTextColor(c or 0xFF000000.toInt())
    }

    private fun pickColor() {
        val names = arrayOf(
            "Material You (system colour)", "Midnight blue", "Black", "Deep purple", "Forest", "Crimson",
            "Custom hex..."
        )
        val colors = intArrayOf(0, 0xFF1B2A41.toInt(), 0xFF000000.toInt(), 0xFF2D1B4E.toInt(),
            0xFF16351F.toInt(), 0xFF4A1420.toInt())
        AlertDialog.Builder(this)
            .setTitle("Tint colour")
            .setItems(names) { _, which ->
                when {
                    which == 0 -> { cfg.tintMode = 0; refreshSwatch() }
                    which < colors.size -> {
                        cfg.tintMode = 1
                        cfg.tintCustom = colors[which]
                        refreshSwatch()
                    }
                    else -> {
                        val input = EditText(this).apply {
                            hint = "#RRGGBB"
                            setSingleLine()
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Hex colour")
                            .setView(input)
                            .setPositiveButton("Set") { _, _ ->
                                try {
                                    cfg.tintCustom = android.graphics.Color.parseColor(input.text.toString().trim())
                                    cfg.tintMode = 1
                                    refreshSwatch()
                                } catch (e: Exception) {
                                    Toast.makeText(this, "Not a valid colour", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- screen radius preview

    private class OutlineView(ctx: android.content.Context) : View(ctx) {
        var radiusPx = 0f
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 6f
            color = 0xFFFF4081.toInt()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val h = paint.strokeWidth / 2f
            canvas.drawRoundRect(h, h, width - h, height - h, radiusPx, radiusPx, paint)
        }
    }

    private var preview: android.app.Dialog? = null
    private var previewView: OutlineView? = null

    private fun showPreview(valueDp: Int) {
        if (preview != null) return
        val v = OutlineView(this)
        v.radiusPx = valueDp * resources.displayMetrics.density
        val dlg = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dlg.setContentView(v)
        dlg.setCancelable(false)
        dlg.window?.apply {
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            )
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
        }
        preview = dlg
        previewView = v
        dlg.show()
    }

    private fun hidePreview() {
        preview?.dismiss()
        preview = null
        previewView = null
    }

    private fun runDiagnostics() {
        Toast.makeText(this, "Reading recents...", Toast.LENGTH_SHORT).show()
        Thread {
            val report = try {
                RecentTasks.debugReport(applicationContext)
            } catch (e: Exception) {
                "Failed: $e"
            }
            runOnUiThread {
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("Root Recents report", report))
                val tv = TextView(this).apply {
                    text = report
                    textSize = 11f
                    setTextIsSelectable(true)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }
                AlertDialog.Builder(this)
                    .setTitle("Copied to clipboard")
                    .setView(ScrollView(this).apply { addView(tv) })
                    .setPositiveButton("Close", null)
                    .show()
            }
        }.start()
    }

    // ---------------------------------------------------------------- row builders

    private fun matchWrap() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun section(name: String) {
        val tv = TextView(this).apply {
            text = name.uppercase()
            textSize = 12f
            alpha = 0.6f
            letterSpacing = 0.08f
        }
        col.addView(tv, matchWrap().apply { topMargin = dp(24); bottomMargin = dp(6) })
    }

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            text = label
            textSize = 16f
            isChecked = value
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        col.addView(sw, matchWrap().apply { topMargin = dp(10); bottomMargin = dp(10) })
    }

    /** A slider with its current value and a small reset button that restores the default. */
    private fun slider(
        label: String,
        spec: Config.IntSpec,
        showPreview: Boolean = false,
        fmt: (Int) -> String
    ) {
        val value = cfg.int(spec)
        val title = TextView(this).apply {
            text = label
            textSize = 16f
        }
        val valueView = TextView(this).apply {
            text = fmt(value)
            textSize = 14f
            alpha = 0.7f
        }
        val bar = SeekBar(this)
        bar.max = spec.hi - spec.lo
        bar.progress = value - spec.lo
        val reset = TextView(this).apply {
            text = "\u21BA"
            textSize = 20f
            setPadding(dp(14), 0, 0, 0)
            setOnClickListener {
                bar.progress = spec.def - spec.lo
                cfg.setInt(spec, spec.def)
                valueView.text = fmt(spec.def)
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueView)
            addView(reset)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress + spec.lo
                valueView.text = fmt(v)
                if (fromUser) cfg.setInt(spec, v)
                previewView?.let {
                    it.radiusPx = v * resources.displayMetrics.density
                    it.invalidate()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                if (showPreview) showPreview(sb.progress + spec.lo)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                if (showPreview) hidePreview()
            }
        })
        col.addView(head, matchWrap().apply { topMargin = dp(10) })
        col.addView(bar, matchWrap().apply { bottomMargin = dp(6) })
    }

    private fun choice(label: String, options: List<String>, current: Int, onPick: (Int) -> Unit) {
        val title = TextView(this).apply {
            text = label
            textSize = 16f
        }
        val valueView = TextView(this).apply {
            text = options[current.coerceIn(0, options.size - 1)]
            textSize = 14f
            alpha = 0.7f
        }
        var selected = current
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueView)
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(label)
                    .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                        selected = which
                        valueView.text = options[which]
                        onPick(which)
                        dialog.dismiss()
                    }
                    .show()
            }
        }
        col.addView(row, matchWrap())
    }

    private fun textRow(label: String, hint: String) {
        val title = TextView(this).apply {
            text = label
            textSize = 16f
        }
        val sub = TextView(this).apply {
            text = hint
            textSize = 13f
            alpha = 0.6f
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(title)
            addView(sub)
            setOnClickListener {
                val input = EditText(this@SettingsActivity).apply {
                    setText(cfg.neverStop)
                    setSingleLine(false)
                    setSelection(text.length)
                }
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(label)
                    .setView(input)
                    .setPositiveButton("Save") { _, _ -> cfg.neverStop = input.text.toString() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        col.addView(row, matchWrap())
    }

    private fun button(label: String, onClick: (View) -> Unit) {
        val b = android.widget.Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener(onClick)
        }
        col.addView(b, matchWrap().apply { topMargin = dp(8) })
    }

    private fun note(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            alpha = 0.6f
        }
        col.addView(tv, matchWrap().apply { topMargin = dp(12) })
    }
}
