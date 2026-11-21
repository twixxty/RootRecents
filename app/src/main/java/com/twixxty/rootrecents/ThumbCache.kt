package com.twixxty.rootrecents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Preview pictures for non-root (Shizuku) mode, where the system's own task snapshots are out of
 * reach: the screenshot taken while an app is in front is kept here and used as its card.
 */
object ThumbCache {

    private fun dir(ctx: Context) = File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    // The package is in the name so a reused task id (after a reboot) never shows the wrong app.
    private fun file(ctx: Context, id: Int, pkg: String) = File(dir(ctx), "${id}_$pkg.jpg")

    fun save(ctx: Context, id: Int, pkg: String, bmp: Bitmap) {
        try {
            val small = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
            file(ctx, id, pkg).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            if (small !== bmp) small.recycle()
        } catch (e: Exception) {
            // no preview then
        }
    }

    fun load(ctx: Context, tasks: List<RecentTask>): Map<Int, Bitmap> {
        val out = HashMap<Int, Bitmap>()
        for (t in tasks) {
            val f = file(ctx, t.id, t.pkg)
            if (!f.exists()) continue
            BitmapFactory.decodeFile(f.path)?.let { out[t.id] = it }
        }
        // forget previews of tasks that are gone
        val live = tasks.map { "${it.id}_${it.pkg}.jpg" }.toSet()
        dir(ctx).listFiles()?.forEach { if (it.name !in live && System.currentTimeMillis() - it.lastModified() > 60_000) it.delete() }
        return out
    }
}
