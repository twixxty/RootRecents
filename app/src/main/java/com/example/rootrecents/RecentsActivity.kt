package com.example.rootrecents

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.min

/**
 * Full-screen, translucent overlay: an iOS-style app switcher.
 * Open it with:  su -c "am start -a com.example.rootrecents.SHOW"
 *
 * The cards live in [CarouselView] (all physics). This class owns the window: blur, dim, loading
 * the task list, and what happens when you tap, dismiss or clear.
 */
class RecentsActivity : Activity() {

    private lateinit var config: Config
    private lateinit var carousel: CarouselView
    private lateinit var empty: TextView

    private val dim = ColorDrawable(0x00000000)
    private val physics = Physics()

    private var blurActive = false
    private var lastBlurPx = -1
    private var backdrop = 0f
    private var backdropM: Motion? = null

    private var launching = false
    private var closing = false

    // loading / "launcher behind" state
    private var loaded: List<RecentTask>? = null
    private var shown = false
    private var spawningHome = false

    private val prefs by lazy { getSharedPreferences("recents", MODE_PRIVATE) }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config.get(this)

        window.setBackgroundDrawable(dim)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            blurActive = config.blur > 0 && windowManager.isCrossWindowBlurEnabled
        }

        setContentView(R.layout.activity_recents)
        carousel = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        carousel.alpha = 0f

        carousel.applyConfig(config)

        carousel.onCardClick = { card -> openTask(card) }
        carousel.onCardLongPress = { task -> openAppInfo(task) }
        carousel.onClearClick = { carousel.clearAll() }
        carousel.onEmptyTap = { closeAnimated() }
        carousel.onDismissed = { tasks -> closeSystemTasks(tasks) }
        carousel.onAllGone = { closeAnimated() }
        empty.setOnClickListener { closeAnimated() }

        load(relaunch = intent.getBooleanExtra("relaunch", false))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("relaunch", false)) {
            // We just put the launcher behind ourselves and came back on top.
            spawningHome = false
            maybeShow()
        } else if (!closing && !launching) {
            // A fresh trigger while already open: start over.
            shown = false
            loaded = null
            carousel.alpha = 0f
            load(relaunch = false)
        }
    }

    override fun onStop() {
        super.onStop()
        // Behave like a system overlay: leaving it (home, another app) dismisses it.
        if (!isFinishing && !spawningHome) finishNoAnim()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = closeAnimated()

    // ---------------------------------------------------------------- loading

    private fun load(relaunch: Boolean) {
        Thread {
            if (!Root.available()) {
                runOnUiThread {
                    empty.setText(R.string.no_root)
                    showEmpty()
                }
                return@Thread
            }
            val hidden = (prefs.getStringSet("hidden", emptySet()) ?: emptySet()).toMutableSet()

            // A fresh screenshot makes the foreground card as up to date as it can be. It has to
            // be taken now, while our window is still empty and the app is still on screen.
            var shot: Bitmap? = null
            val shotThread = if (config.showForeground && !relaunch) {
                Thread { shot = Root.screenshot() }.also { it.start() }
            } else null

            val result = RecentTasks.load(this, hidden, config.showForeground, null)
            shotThread?.join()

            // Forget hidden ids whose tasks no longer exist.
            hidden.retainAll(result.allIds.map { it.toString() }.toSet())
            prefs.edit().putStringSet("hidden", hidden).apply()

            var patched = false
            val tasks = result.tasks.map { t ->
                val s = shot
                if (t.foreground && s != null && !patched) {
                    patched = true
                    t.copy(thumb = s)
                } else t
            }
            // Oldest on the left, most recent on the right, like stock.
            val ordered = tasks.reversed()

            val putLauncherBehind = config.showForeground && config.launcherBehind &&
                !relaunch && ordered.any { it.foreground }
            if (putLauncherBehind) {
                spawningHome = true
                runOnUiThread {
                    carousel.postDelayed({ if (spawningHome) finishNoAnim() }, 3000) // safety net
                }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                loaded = ordered
                maybeShow()
            }
            if (putLauncherBehind) {
                Actions.goHome()
                Thread.sleep(260)
                Actions.bringRecentsBack(packageName)
            }
        }.start()
    }

    private fun maybeShow() {
        val tasks = loaded ?: return
        if (spawningHome || shown) return
        shown = true
        show(tasks)
    }

    private fun show(tasks: List<RecentTask>) {
        if (tasks.isEmpty()) {
            showEmpty()
            return
        }
        empty.visibility = View.GONE
        carousel.setTasks(tasks)
        carousel.post {
            val style = when (config.enterStyle) {
                0 -> CarouselView.EnterStyle.RISE
                2 -> CarouselView.EnterStyle.SLIDE_RIGHT
                3 -> CarouselView.EnterStyle.ZOOM
                else -> CarouselView.EnterStyle.SLIDE_LEFT
            }
            // Entry offsets are applied first, then the view is revealed: no flash of the cards
            // sitting at rest before they animate in.
            carousel.playEnter(style)
            carousel.alpha = 1f
            animateBackdrop(1f)
        }
    }

    private fun showEmpty() {
        carousel.visibility = View.GONE
        empty.visibility = View.VISIBLE
        empty.alpha = 0f
        physics.start(SpringMotion(0f, 0f, 1f, 12f, 1f, 0.002f, 0.02f) { empty.alpha = it })
        animateBackdrop(1f)
    }

    // ---------------------------------------------------------------- backdrop (blur + dim)

    private fun setBackdrop(p: Float) {
        backdrop = p
        val c = p.coerceIn(0f, 1f)
        // Without blur, darken more so the cards still stand out.
        val dimAlpha = min(0.85f, config.dimAlpha * (if (blurActive) 1f else 1.5f))
        dim.color = ((dimAlpha * c * 255f).toInt()) shl 24
        if (blurActive && Build.VERSION.SDK_INT >= 31) {
            val px = (config.blurRadiusPx * c).toInt()
            if (px != lastBlurPx) {
                lastBlurPx = px
                val lp = window.attributes
                lp.blurBehindRadius = px
                window.attributes = lp
            }
        }
    }

    private fun animateBackdrop(to: Float, onEnd: (() -> Unit)? = null) {
        physics.cancel(backdropM)
        val m = SpringMotion(backdrop, 0f, to, 12f, 1f, 0.002f, 0.02f) { setBackdrop(it) }
        m.onEnd = onEnd
        backdropM = m
        physics.start(m)
    }

    // ---------------------------------------------------------------- actions

    private fun closeAnimated() {
        if (closing || launching) return
        closing = true
        carousel.playExit { }
        empty.animate().alpha(0f).setDuration(140).start()
        animateBackdrop(0f) { finishNoAnim() }
        carousel.postDelayed({ finishNoAnim() }, 1200) // safety net
    }

    /** Tap on a card: it grows to fill the screen while the real app is brought forward. */
    private fun openTask(card: CarouselView.Card) {
        val task = card.task ?: return
        if (launching || closing) return
        launching = true

        // Start the real app straight away so it is ready underneath when the card arrives.
        // NO_ANIMATION: our growing card is the transition, Android's own slide must not play.
        val appCtx = applicationContext
        Thread { Actions.launch(appCtx, task) }.start()

        carousel.openCard(card) {
            animateBackdrop(0f)
            // Hold the full-screen card for a moment so a slow app start never shows a gap.
            carousel.postDelayed({ finishNoAnim() }, 220)
        }
        carousel.postDelayed({ finishNoAnim() }, 1800) // safety net
    }

    /** Long-press on the app icon / name opens that app's system "App info" page. */
    private fun openAppInfo(task: RecentTask) {
        if (launching || closing) return
        Thread { Actions.openAppInfo(task) }.start()
        closeAnimated()
    }

    /** Remove from the real recents list (and optionally force-stop), then remember it. */
    private fun closeSystemTasks(tasks: List<RecentTask>) {
        val s = HashSet(prefs.getStringSet("hidden", emptySet()) ?: emptySet())
        tasks.forEach { s.add(it.id.toString()) }
        prefs.edit().putStringSet("hidden", s).apply()

        val appCtx = applicationContext
        val doStop = config.forceStop
        val never = config.neverStopSet
        Thread {
            Actions.removeTasks(appCtx, tasks.map { it.id })
            if (doStop) Actions.forceStop(tasks, never)
        }.start()
    }

    private fun finishNoAnim() {
        if (isFinishing) return
        finish()
        overridePendingTransition(0, 0)
    }
}
