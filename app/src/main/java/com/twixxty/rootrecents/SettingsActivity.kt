package com.twixxty.rootrecents

import android.app.Activity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
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
        Root.init(applicationContext)

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(32))
        }
        val pageBg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0)
        window.decorView.setBackgroundColor(pageBg)
        window.statusBarColor = pageBg
        window.navigationBarColor = pageBg
        if (!MaterialYou.isNight(this)) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(col)
            setOnApplyWindowInsetsListener { v, ins ->
                @Suppress("DEPRECATION")
                v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
                ins
            }
        })
        header()

        section("Access")
        choice("Run commands with", listOf("Automatic", "Root", "Shizuku (no root)"), cfg.backend) {
            cfg.backend = it
            Root.init(applicationContext)
            Root.reset()
            refreshAccess()
        }
        accessRow()
        button("Grant Shizuku permission") { requestShizuku() }
        note(
            "Root gives everything. Shizuku works without root (start it first, from the Shizuku app): " +
                "everything works except the system's own app snapshots, so cards show a preview of an " +
                "app only after you have used it with the bottom trigger on."
        )

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
        choice("Button under the cards", listOf("Screenshot", "Clear all", "Nothing", "Both"), cfg.bottomButton) { cfg.bottomButton = it }
        toggle("Compact pill with icons (always used when both buttons are shown)", cfg.bottomPill) { cfg.bottomPill = it }
        slider("Screenshot flash: border thickness", Config.SHOT_BORDER) { "$it dp" }
        toggle("\"Clear all\" at the left end of the cards", cfg.clearLeft) { cfg.clearLeft = it }

        section("Feel")
        slider("Scroll friction (higher = stops sooner)", Config.SCROLL_FRICTION) { "$it" }
        slider("Animation speed", Config.ANIM_SPEED) { "$it%" }
        slider("Bounciness", Config.BOUNCE) { "$it%" }
        slider("Stack elasticity", Config.ELASTIC) { "$it%" }
        slider("Stack spread", Config.STACK_SPREAD) { "$it%" }
        slider("Neighbour lift when swiping a card up", Config.LIFT) { "$it%" }
        slider("Settle speed after swiping up from the bottom", Config.PULL_GRAVITY) { "$it%" }
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

        slider("Card to app: expand speed", Config.OPEN_SPEED) { "$it%" }
        slider("Card to app: hold before fade", Config.OPEN_HOLD) { "$it ms" }
        slider("Card to app: fade duration", Config.OPEN_FADE) { "$it ms" }

        section("Hidden apps")
        button("Choose apps to hide from the switcher") { pickHiddenApps() }
        note("Hidden apps never show up in the switcher (they stay open in the background).")

        section("Foreground app")
        toggle("Show the app that is in the foreground", cfg.showForeground) { cfg.showForeground = it }

        section("Bottom trigger")
        toggle("Enable the bottom swipe trigger", cfg.triggerEnabled) { setTrigger(it) }
        toggle("Read touches with root (turn on if the swipe doesn't work from the gesture handle, e.g. ColorOS)", cfg.rawTouch) {
            cfg.rawTouch = it
            TriggerAccessibilityService.instance?.rebuild()
        }
        toggle("Only work like the stock gestures (experimental: only while the navigation bar is showing; swipe it into view first in full-screen apps)", cfg.stockLike) {
            cfg.stockLike = it
            TriggerAccessibilityService.instance?.rebuild()
        }
        toggle("Misclick protection (a swipe up, or swipe up and hold, only shows a hint; do it again to trigger)", cfg.misclick) { cfg.misclick = it }
        toggle("Blur the screen while going home (the screen blurs, the system's home animation plays, then it clears)", cfg.homeAnim) { cfg.homeAnim = it }
        slider("Home: blur strength", Config.HOME_BLUR) { "$it%" }
        slider("Home: time to blur", Config.HOME_BLUR_IN) { "$it ms" }
        slider("Home: wait at the home screen before clearing", Config.HOME_HOLD) { "$it ms" }
        slider("Home: time to clear the blur", Config.HOME_BLUR_OUT) { "$it ms" }
        toggle("Show the peek animation while holding", cfg.triggerHold) { cfg.triggerHold = it }
        slider("Peek intensity (how far the cards follow your finger)", Config.PEEK_INTENSITY) { "$it%" }
        toggle("Swipe right along the bottom to show the previous app", cfg.quickSwitch) { cfg.quickSwitch = it }
        slider("Sensitivity (height of the touch zone)", Config.TRIG_ZONE) { "$it dp" }
        slider("Swipe length", Config.TRIG_LENGTH) { "$it dp" }
        slider("Hold time", Config.TRIG_HOLD) { "$it ms" }
        note(
            "Uses an accessibility service (switched on for you through root) so it keeps working " +
                "over apps like system Settings that hide normal overlays. " +
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
            MaterialAlertDialogBuilder(this)
                .setTitle("Reset all settings?")
                .setMessage("Every option goes back to its default.")
                .setPositiveButton("Reset") { _, _ ->
                    Thread { Actions.setTriggerService(false) }.start()
                    cfg.resetAll()
                    recreate()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        note(
            "Open from a root shell with:\nam start -a com.twixxty.rootrecents.SHOW\n\n" +
                "Settings are read each time recents opens."
        )
    }

    override fun onResume() {
        super.onResume()
        refreshAccess()
    }

    override fun onPause() {
        super.onPause()
        // Rebuild the trigger strip so new size / length / hold values apply.
        TriggerAccessibilityService.instance?.rebuild()
    }

    private fun setTrigger(on: Boolean) {
        cfg.triggerEnabled = on
        val app = applicationContext
        Thread {
            Actions.setTriggerService(on)
            if (on) {
                Thread.sleep(1200)
                runOnUiThread {
                    val svc = TriggerAccessibilityService.instance
                    if (svc == null) {
                        Toast.makeText(
                            this,
                            "Couldn't switch on the accessibility service. Enable \"Root Recents\" in " +
                                "Settings > Accessibility.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else svc.rebuild()
                }
            } else {
                runOnUiThread { TriggerAccessibilityService.instance?.rebuild() }
            }
        }.start()
    }

    // ---------------------------------------------------------------- colour

    private var swatch: TextView? = null

    private fun colorRow() {
        val sw = TextView(this).apply { textSize = 14f }
        swatch = sw
        refreshSwatch()
        row("Tint colour", sw) { pickColor() }
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
        MaterialAlertDialogBuilder(this)
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
                        MaterialAlertDialogBuilder(this)
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
                MaterialAlertDialogBuilder(this)
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

    private fun pickHiddenApps() {
        Thread {
            val pm = packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            class App(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable)
            val apps = pm.queryIntentActivities(intent, 0)
                .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
                .distinctBy { it.pkg }
                .filter { it.pkg != packageName }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                val hidden = cfg.hiddenAppsSet.toMutableSet()
                val list = android.widget.ListView(this)
                list.divider = null
                list.adapter = object : android.widget.BaseAdapter() {
                    override fun getCount() = apps.size
                    override fun getItem(i: Int) = apps[i]
                    override fun getItemId(i: Int) = i.toLong()
                    override fun getView(i: Int, cv: View?, parent: ViewGroup): View {
                        val app = apps[i]
                        val rowView = LinearLayout(this@SettingsActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setPadding(dp(24), dp(8), dp(16), dp(8))
                        }
                        rowView.addView(android.widget.ImageView(this@SettingsActivity).apply {
                            setImageDrawable(app.icon)
                        }, LinearLayout.LayoutParams(dp(40), dp(40)))
                        rowView.addView(TextView(this@SettingsActivity).apply {
                            text = app.label
                            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                            setTextColor(aOnSurface)
                            setPadding(dp(16), 0, dp(8), 0)
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        rowView.addView(MaterialCheckBox(this@SettingsActivity).apply {
                            isChecked = app.pkg in hidden
                            isClickable = false
                            isFocusable = false
                        })
                        return rowView
                    }
                }
                list.setOnItemClickListener { _, _, i, _ ->
                    val pkg = apps[i].pkg
                    if (!hidden.add(pkg)) hidden.remove(pkg)
                    (list.adapter as android.widget.BaseAdapter).notifyDataSetChanged()
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle("Hide from the switcher")
                    .setView(list)
                    .setPositiveButton("Save") { _, _ -> cfg.hiddenApps = hidden.joinToString(",") }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    // ---------------------------------------------------------------- Material 3 expressive rows

    private var grp: LinearLayout? = null
    private var accessView: TextView? = null

    private fun attr(a: Int) = MaterialColors.getColor(col, a)

    private val aSurfaceContainer get() = attr(com.google.android.material.R.attr.colorSurfaceContainer)
    private val aOnSurface get() = attr(com.google.android.material.R.attr.colorOnSurface)
    private val aOnSurfaceVariant get() = attr(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val aPrimary get() = attr(androidx.appcompat.R.attr.colorPrimary)
    private val aSecContainer get() = attr(com.google.android.material.R.attr.colorSecondaryContainer)
    private val aOnSecContainer get() = attr(com.google.android.material.R.attr.colorOnSecondaryContainer)

    private fun put(v: View, lp: LinearLayout.LayoutParams = matchWrap()) {
        (grp ?: col).addView(v, lp)
    }

    private fun roundRect(color: Int, radiusDp: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun title(t: String) = TextView(this).apply {
        text = t
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        setTextColor(aOnSurface)
    }

    private fun subtitle(t: String) = TextView(this).apply {
        text = t
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(aOnSurfaceVariant)
    }

    /** "Name (explanation)" labels become a title with a smaller line under it. */
    private fun textBlock(label: String): LinearLayout {
        var main = label
        var sub: String? = null
        val k = label.indexOf(" (")
        if (k > 0 && label.endsWith(")")) {
            main = label.substring(0, k)
            sub = label.substring(k + 2, label.length - 1).replaceFirstChar { it.uppercase() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title(main))
            if (sub != null) addView(subtitle(sub), matchWrap().apply { topMargin = dp(2) })
        }
    }

    private fun chip(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setTextColor(aOnSecContainer)
        background = roundRect(aSecContainer, 16)
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    private fun header() {
        col.addView(TextView(this).apply {
            text = "Root Recents"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_DisplaySmall)
            setTextColor(aOnSurface)
        }, matchWrap().apply { topMargin = dp(40); bottomMargin = dp(2); leftMargin = dp(8) })
        col.addView(TextView(this).apply {
            text = "Version ${packageManager.getPackageInfo(packageName, 0).versionName}"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(aOnSurfaceVariant)
        }, matchWrap().apply { leftMargin = dp(8) })
    }

    private fun section(name: String) {
        col.addView(TextView(this).apply {
            text = name
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setTextColor(aPrimary)
        }, matchWrap().apply { topMargin = dp(28); bottomMargin = dp(8); leftMargin = dp(12) })
        val card = MaterialCardView(this).apply {
            radius = dp(28).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(aSurfaceContainer)
        }
        val g = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        card.addView(g)
        col.addView(card, matchWrap())
        grp = g
    }

    /** A row with text on the left and an optional control on the right. */
    private fun row(label: String, trailing: View?, onClick: (() -> Unit)? = null): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(textBlock(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) addView(trailing, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(12) })
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
        put(r)
        return r
    }

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val sw = MaterialSwitch(this).apply {
            isChecked = value
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        val r = row(label, sw)
        r.isClickable = true
        r.setOnClickListener { sw.toggle() }
    }

    /** A Material slider with its value and a small reset that restores the default. */
    private fun slider(
        label: String,
        spec: Config.IntSpec,
        showPreview: Boolean = false,
        fmt: (Int) -> String
    ) {
        val value = cfg.int(spec)
        val valueView = chip(fmt(value))
        val bar = Slider(this).apply {
            valueFrom = spec.lo.toFloat()
            valueTo = spec.hi.toFloat()
            stepSize = 1f
            this.value = value.toFloat()
            setLabelFormatter { v -> fmt(v.toInt()) }
        }
        val reset = TextView(this).apply {
            text = "\u21BA"
            textSize = 20f
            setTextColor(aOnSurfaceVariant)
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener {
                bar.value = spec.def.toFloat()
                cfg.setInt(spec, spec.def)
                valueView.text = fmt(spec.def)
                TriggerAccessibilityService.instance?.rebuild()
            }
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(valueView)
            addView(reset)
        }
        bar.addOnChangeListener { _, v, fromUser ->
            val iv = v.toInt()
            valueView.text = fmt(iv)
            if (fromUser) cfg.setInt(spec, iv)
            previewView?.let {
                it.radiusPx = iv * resources.displayMetrics.density
                it.invalidate()
            }
        }
        bar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                if (showPreview) showPreview(slider.value.toInt())
            }

            override fun onStopTrackingTouch(slider: Slider) {
                TriggerAccessibilityService.instance?.rebuild()
                if (showPreview) hidePreview()
            }
        })
        val r = row(label, right)
        r.setPadding(0, dp(12), 0, 0)
        put(bar, matchWrap().apply { bottomMargin = dp(4) })
    }

    /** Short lists become a connected button group; longer ones open a Material dialog. */
    private fun choice(label: String, options: List<String>, current: Int, onPick: (Int) -> Unit) {
        val cur = current.coerceIn(0, options.size - 1)
        if (options.size <= 3 && options.all { it.length <= 18 }) {
            val r = row(label, null)
            val group = MaterialButtonToggleGroup(this).apply {
                isSingleSelection = true
                isSelectionRequired = true
            }
            val ids = options.map { View.generateViewId() }
            options.forEachIndexed { i, o ->
                group.addView(MaterialButton(
                    this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    id = ids[i]
                    text = o
                    isAllCaps = false
                })
            }
            group.check(ids[cur])
            group.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) onPick(ids.indexOf(checkedId))
            }
            put(group, matchWrap().apply { bottomMargin = dp(12) })
            return
        }
        var selected = cur
        val valueView = chip(options[cur])
        row(label, valueView) {
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(label.substringBefore(" ("))
                .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                    selected = which
                    valueView.text = options[which]
                    onPick(which)
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun textRow(label: String, hint: String) {
        row("$label ($hint)", null) {
            val input = EditText(this@SettingsActivity).apply {
                setText(cfg.neverStop)
                setSingleLine(false)
                setSelection(text.length)
            }
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(label)
                .setView(input)
                .setPositiveButton("Save") { _, _ -> cfg.neverStop = input.text.toString() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Filled tonal button. */
    private fun button(label: String, onClick: (View) -> Unit) {
        val b = MaterialButton(this).apply {
            text = label
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(aSecContainer)
            setTextColor(aOnSecContainer)
            setOnClickListener(onClick)
        }
        put(b, matchWrap().apply { topMargin = dp(8); bottomMargin = dp(4) })
    }

    private fun note(text: String) {
        put(subtitle(text).apply { textSize = 12.5f }, matchWrap().apply { topMargin = dp(8); bottomMargin = dp(8) })
    }

    // ---------------------------------------------------------------- access (root / Shizuku)

    private fun accessRow() {
        val tv = subtitle("Checking...")
        accessView = tv
        put(tv, matchWrap().apply { topMargin = dp(4); bottomMargin = dp(8) })
        refreshAccess()
    }

    private fun refreshAccess() {
        val tv = accessView ?: return
        Thread {
            Root.reset()
            val root = Root.rootWorks()
            val running = Root.shizukuRunning()
            val granted = Root.shizukuReady()
            val using = when (Root.backend()) {
                Root.ROOT -> "root"
                Root.SHIZUKU -> "Shizuku"
                else -> "nothing yet"
            }
            val text = "Root: " + (if (root) "available" else "not available") +
                "\nShizuku: " + (if (!running) "not running" else if (granted) "ready" else "running, permission needed") +
                "\nUsing: $using"
            runOnUiThread { tv.text = text }
        }.start()
    }

    private fun requestShizuku() {
        if (!Root.shizukuRunning()) {
            Toast.makeText(this, "Start Shizuku first (open the Shizuku app).", Toast.LENGTH_LONG).show()
            return
        }
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(
                object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(this)
                        runOnUiThread { refreshAccess() }
                    }
                }
            )
            rikka.shizuku.Shizuku.requestPermission(7)
        } catch (e: Throwable) {
            Toast.makeText(this, "Couldn't ask Shizuku: $e", Toast.LENGTH_LONG).show()
        }
    }
}
