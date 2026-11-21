package com.example.rootrecents

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The task list ready to show, oldest first (thumbnails arrive separately). */
class Loaded(val tasks: List<RecentTask>, val noRoot: Boolean)

/**
 * Loads the task list in two phases so the cards can appear immediately: first the list (one quick
 * root call), then the screenshots, which are filled in as soon as they are decoded. The bottom
 * trigger starts this the moment a finger touches the strip.
 */
object Prefetch {

    class Job(val startedAt: Long) {
        @Volatile
        var result: Loaded? = null
        @Volatile
        var thumbs: Map<Int, Bitmap> = emptyMap()
        val listLatch = CountDownLatch(1)
        val thumbLatch = CountDownLatch(1)

        fun await(): Loaded? {
            listLatch.await(10, TimeUnit.SECONDS)
            return result
        }

        fun awaitThumbs(): Map<Int, Bitmap> {
            thumbLatch.await(20, TimeUnit.SECONDS)
            return thumbs
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
                run(app, j)
            } catch (e: Exception) {
                if (j.result == null) j.result = Loaded(emptyList(), false)
            } finally {
                j.listLatch.countDown()
                j.thumbLatch.countDown()
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

    private fun run(ctx: Context, j: Job) {
        val config = Config.get(ctx)
        val prefs = ctx.getSharedPreferences("recents", Context.MODE_PRIVATE)
        val hidden = (prefs.getStringSet("hidden", emptySet()) ?: emptySet()).toMutableSet()

        // A fresh screenshot makes the foreground card as up to date as it can be; it runs alongside.
        var shot: Bitmap? = null
        val shotThread = if (config.showForeground) {
            Thread { shot = Root.screenshot() }.also { it.start() }
        } else null

        val listing = RecentTasks.loadList(ctx, hidden, config.showForeground, config.hiddenAppsSet)
        if (listing.noRoot) {
            shotThread?.interrupt()
            j.result = Loaded(emptyList(), true)
            return
        }

        // Forget hidden ids whose tasks no longer exist.
        hidden.retainAll(listing.allIds.map { it.toString() }.toSet())
        prefs.edit().putStringSet("hidden", hidden).apply()

        // Oldest on the left, most recent on the right, like stock.
        val tasks = listing.tasks.reversed()
        j.result = Loaded(tasks, false)
        j.listLatch.countDown()

        // Newest first is irrelevant (one batch), but only ask for files of tasks we show.
        val wanted = listing.tasks.map { it.id }.toSet()
        val map = HashMap(RecentTasks.loadThumbs(listing.paths.filterKeys { it in wanted }))
        shotThread?.join()
        val s = shot
        if (s != null && listing.fgId != -1 && listing.fgId in wanted) map[listing.fgId] = s
        j.thumbs = map
    }
}
