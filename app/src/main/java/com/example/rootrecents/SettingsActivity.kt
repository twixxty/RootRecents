package com.example.rootrecents

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
        choice("Style", listOf("Cards (flat row)", "Stack (iOS)"), cfg.layoutMode) { cfg.layoutMode = it }
        toggle("Snap to cards (flat row only, the stack never snaps)", cfg.snap) { cfg.snap = it }
        choice(
            "Opening animation",
            listOf("Rise", "Slide in from the left", "Slide in from the right", "Zoom"),
            cfg.enterStyle
        ) { cfg.enterStyle = it }
        slider("Card size", 45, 75, cfg.cardSize, { "$it%" }) { cfg.cardSize = it }
        toggle("Show app icon and name above cards", cfg.showLabels) { cfg.showLabels = it }

        section("Background")
        slider("Blur", 0, 100, cfg.blur, { "$it%" }) { cfg.blur = it }
        slider("Darkening", 0, 80, cfg.dim, { "$it%" }) { cfg.dim = it }

        section("Foreground app")
        toggle("Show the app that is in the foreground", cfg.showForeground) { cfg.showForeground = it }
        toggle(
            "Put the launcher behind (needs the option above)",
            cfg.launcherBehind
        ) { cfg.launcherBehind = it }
        note(
            "With the foreground app shown, its card uses a fresh screenshot taken as the switcher " +
                "opens. A truly live tile is not possible once the launcher is behind it, because " +
                "Android stops drawing apps that are not on screen."
        )

        section("Feel")
        toggle("Haptic feedback", cfg.haptics) { cfg.haptics = it }

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
        note(
            "Open from a root shell with:\nam start -a com.example.rootrecents.SHOW\n\n" +
                "Settings are read each time recents opens."
        )
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

    private fun slider(
        label: String,
        lo: Int,
        hi: Int,
        value: Int,
        fmt: (Int) -> String,
        onChange: (Int) -> Unit
    ) {
        val title = TextView(this).apply {
            text = label
            textSize = 16f
        }
        val valueView = TextView(this).apply {
            text = fmt(value)
            textSize = 14f
            alpha = 0.7f
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueView)
        }
        val bar = SeekBar(this)
        bar.max = hi - lo
        bar.progress = value - lo
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress + lo
                valueView.text = fmt(v)
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
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
