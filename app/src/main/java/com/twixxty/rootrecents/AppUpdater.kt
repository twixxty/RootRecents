package com.twixxty.rootrecents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for new releases of RootRecents, downloads the APK, and installs it
 * via root/Shizuku (silent update) or through the system package installer.
 */
object AppUpdater {

    const val REPO_OWNER = "twixxty"
    const val REPO_NAME = "RootRecents"
    private const val API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    data class UpdateInfo(
        val tagName: String,
        val releaseName: String,
        val changelog: String,
        val apkName: String,
        val downloadUrl: String,
        val size: Long
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    fun currentVersion(ctx: Context): String {
        return try {
            val pInfo = if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            }
            pInfo.versionName ?: "0.32"
        } catch (e: Exception) {
            "0.32"
        }
    }

    /**
     * Checks if [remoteTag] is strictly newer than [currentVer].
     * Supports formats like "0.33", "v0.33", "0.33.1", etc.
     */
    fun isNewer(remoteTag: String, currentVer: String): Boolean {
        val rClean = remoteTag.removePrefix("v").trim()
        val cClean = currentVer.removePrefix("v").trim()
        val rParts = rClean.split('.').mapNotNull { it.toIntOrNull() }
        val cParts = cClean.split('.').mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * Queries the latest release on GitHub in a background thread.
     */
    fun checkUpdate(ctx: Context, callback: (Result<UpdateInfo?>) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val url = URL(API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "RootRecents-AppUpdater")
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val code = conn.responseCode
                if (code != 200) {
                    throw IOException("HTTP $code from GitHub API: ${conn.responseMessage}")
                }

                val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val root = JSONObject(jsonText)
                val tag = root.optString("tag_name", "").removePrefix("v")
                val name = root.optString("name", "v$tag")
                val body = root.optString("body", "").trim()

                val cur = currentVersion(app)
                if (!isNewer(tag, cur)) {
                    mainHandler.post { callback(Result.success(null)) }
                    return@Thread
                }

                val assets = root.optJSONArray("assets")
                var downloadUrl: String? = null
                var apkName = "RootRecents-v$tag.apk"
                var apkSize = 0L

                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val aName = asset.optString("name", "")
                        if (aName.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url")
                            apkName = aName
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                if (downloadUrl.isNullOrBlank()) {
                    throw IOException("No APK found in release v$tag assets")
                }

                val info = UpdateInfo(
                    tagName = tag,
                    releaseName = name,
                    changelog = body,
                    apkName = apkName,
                    downloadUrl = downloadUrl,
                    size = apkSize
                )
                mainHandler.post { callback(Result.success(info)) }
            } catch (e: Exception) {
                mainHandler.post { callback(Result.failure(e)) }
            }
        }.start()
    }

    /**
     * Downloads the APK with progress reporting and initiates installation.
     */
    fun downloadAndInstall(
        ctx: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onComplete: (Result<File>) -> Unit
    ) {
        val app = ctx.applicationContext
        Thread {
            try {
                var currentUrl = info.downloadUrl
                var conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.apply {
                    setRequestProperty("User-Agent", "RootRecents-AppUpdater")
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                // Handle redirects manually if needed
                var code = conn.responseCode
                var redirects = 0
                while ((code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) && redirects++ < 5) {
                    currentUrl = conn.getHeaderField("Location")
                    conn.disconnect()
                    conn = URL(currentUrl).openConnection() as HttpURLConnection
                    conn.apply {
                        setRequestProperty("User-Agent", "RootRecents-AppUpdater")
                        instanceFollowRedirects = true
                        connectTimeout = 15000
                        readTimeout = 15000
                    }
                    code = conn.responseCode
                }

                if (code != 200) {
                    throw IOException("Failed to download APK: HTTP $code")
                }

                val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.size
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                val targetFile = File(dir, info.apkName)

                conn.inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        var downloaded = 0L
                        var lastReported = -1
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != lastReported) {
                                    lastReported = pct
                                    mainHandler.post { onProgress(pct) }
                                }
                            }
                        }
                        output.flush()
                    }
                }
                conn.disconnect()

                mainHandler.post {
                    onComplete(Result.success(targetFile))
                    installApk(app, targetFile)
                }
            } catch (e: Exception) {
                mainHandler.post { onComplete(Result.failure(e)) }
            }
        }.start()
    }

    /**
     * Tries silent install via Root / Shizuku first; falls back to standard package installer.
     */
    fun installApk(ctx: Context, file: File) {
        val app = ctx.applicationContext
        Thread {
            try {
                // If Root or Shizuku shell is available, install directly with pm
                val rootRes = Root.exec("pm install -r \"${file.absolutePath}\"")
                if (rootRes.contains("Success", ignoreCase = true)) {
                    mainHandler.post {
                        android.widget.Toast.makeText(app, "RootRecents updated successfully!", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
            } catch (e: Throwable) {
                // fallback to package installer below
            }

            mainHandler.post {
                try {
                    val authority = "${app.packageName}.fileprovider"
                    val uri: Uri = FileProvider.getUriForFile(app, authority, file)
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    app.startActivity(installIntent)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(app, "Could not launch package installer: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
