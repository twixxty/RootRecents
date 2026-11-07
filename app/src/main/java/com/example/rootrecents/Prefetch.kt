package com.example.rootrecents

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The task list ready to show, oldest first. */
class Loaded(val tasks: List<RecentTask>, val noRoot: Boolean)

/**
 * Loads the task list. The bottom trigger starts this the moment a finger touches the strip, so by
 * the time the swipe has finished and the switcher is on screen the list is usually ready.
 */
object Prefetch {

    class Job(val startedAt: Long) {
        @Volatile
        var result: Loaded? = null
        val latch = CountDownLatch(1)

        fun await(): Loaded? {
            latch.await(10, TimeUnit.SECONDS)
            return result
        }
    }

    @Volatile
    private var job: Job? = null

    fun begin(ctx: Context): Job {
        val app = ctx.applicationContext
        val j = Job(System.currentTimeMillis())
        job = j
        Thread {
            try {
                j.result = load(app)
            } catch (e: Exception) {
                j.result = Loaded(emptyList(), false)
            } finally {
                j.latch.countDown()
            }
        }.start()
        return j
    }

    /** A recent enough prefetch, if there is one (it can still be running: call await()). */
    fun take(): Job? {
        val j = job
        job = null
        return if (j != null && System.currentTimeMillis() - j.startedAt < 6000) j else null
    }

    private fun load(ctx: Context): Loaded {
        val config = Config.get(ctx)
        val prefs = ctx.getSharedPreferences("recents", Context.MODE_PRIVATE)
        val hidden = (prefs.getStringSet("hidden", emptySet()) ?: emptySet()).toMutableSet()

        // A fresh screenshot makes the foreground card as up to date as it can be.
        var shot: Bitmap? = null
        val shotThread = if (config.showForeground) {
            Thread { shot = Root.screenshot() }.also { it.start() }
        } else null

        val result = RecentTasks.load(ctx, hidden, config.showForeground, null)
        shotThread?.join()
        if (result.noRoot) return Loaded(emptyList(), true)

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
        return Loaded(tasks.reversed(), false)
    }
}
