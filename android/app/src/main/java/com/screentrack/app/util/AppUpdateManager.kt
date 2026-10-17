package com.screentrack.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val versionName: String,
    val tagName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkSize: Long
)

object AppUpdateManager {

    private const val GITHUB_REPO = "Hassan-711/screentracker"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Checks GitHub for the latest release. Returns AppUpdateInfo if a newer version is available.
     */
    suspend fun checkForUpdate(currentVersion: String): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val url = URL(RELEASES_API_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "ScreenTrack-Android-App")
            }

            val code = conn.responseCode
            if (code == 404) {
                // No releases yet in the repository
                return@withContext Result.success(null)
            }
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                return@withContext Result.failure(Exception("GitHub API returned $code: $err"))
            }

            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val root = json.parseToJsonElement(responseText).jsonObject

            val tagName = root["tag_name"]?.jsonPrimitive?.content ?: ""
            val releaseTitle = root["name"]?.jsonPrimitive?.content ?: tagName
            val releaseNotes = root["body"]?.jsonPrimitive?.content ?: "Performance improvements and bug fixes."

            // Clean version string e.g. "v2.0.1" -> "2.0.1"
            val targetVersion = tagName.trimStart('v', 'V').trim()
            val cleanCurrent = currentVersion.trimStart('v', 'V').trim()

            if (!isNewerVersion(cleanCurrent, targetVersion)) {
                // Already on the latest version or higher
                return@withContext Result.success(null)
            }

            // Find first APK asset in release
            val assets = root["assets"]?.jsonArray ?: return@withContext Result.success(null)
            var apkDownloadUrl: String? = null
            var apkSize = 0L

            for (assetElem in assets) {
                val assetObj = assetElem.jsonObject
                val name = assetObj["name"]?.jsonPrimitive?.content ?: ""
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkDownloadUrl = assetObj["browser_download_url"]?.jsonPrimitive?.content
                    apkSize = assetObj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    break
                }
            }

            if (apkDownloadUrl.isNullOrEmpty()) {
                return@withContext Result.success(null)
            }

            Result.success(
                AppUpdateInfo(
                    versionName = targetVersion,
                    tagName = tagName,
                    releaseTitle = releaseTitle,
                    releaseNotes = releaseNotes,
                    apkDownloadUrl = apkDownloadUrl,
                    apkSize = apkSize
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Semver comparator: returns true if targetVersion > currentVersion
     */
    fun isNewerVersion(currentVersion: String, targetVersion: String): Boolean {
        val currentParts = currentVersion.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val targetParts = targetVersion.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }

        val maxLength = maxOf(currentParts.size, targetParts.size)
        for (i in 0 until maxLength) {
            val curr = currentParts.getOrElse(i) { 0 }
            val targ = targetParts.getOrElse(i) { 0 }
            if (targ > curr) return true
            if (targ < curr) return false
        }
        return false
    }

    /**
     * Downloads the APK file to the internal cache directory with progress reporting.
     * Cleans up any prior APKs before starting to conserve storage.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (progress: Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "apk_updates")
        // Clean any leftover old downloads first
        cleanupDownloadedApks(context)

        if (!updateDir.exists()) {
            updateDir.mkdirs()
        }

        val destinationFile = File(updateDir, "screentrack-update.apk")
        var connection: HttpURLConnection? = null
        try {
            var currentUrl = downloadUrl
            // Follow redirects (GitHub asset releases redirect to AWS S3)
            var redirects = 0
            while (redirects < 5) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "ScreenTrack-Android-App")
                }
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 || code == 308
                ) {
                    currentUrl = connection.getHeaderField("Location") ?: break
                    connection.disconnect()
                    redirects++
                } else {
                    break
                }
            }

            val conn = connection ?: return@withContext Result.failure(Exception("Failed to open connection"))
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                return@withContext Result.failure(Exception("Download failed with HTTP code $responseCode"))
            }

            val totalBytes = conn.contentLengthLong
            val inputStream = conn.inputStream
            val outputStream = FileOutputStream(destinationFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L

            inputStream.use { input ->
                outputStream.use { output ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val progress = (totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress)
                        }
                    }
                    output.flush()
                }
            }

            Result.success(destinationFile)
        } catch (e: Exception) {
            destinationFile.delete()
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Checks if the app can install unknown packages (Android 8.0+).
     */
    fun canInstallApk(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Directs the user to system settings to grant unknown apps installation permission.
     */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            }
        }
    }

    /**
     * Launches Android Package Installer using FileProvider content URI.
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) return

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Recursively deletes all downloaded update files to ensure zero storage bloat.
     * Invoked automatically on startup and post-installation.
     */
    fun cleanupDownloadedApks(context: Context) {
        try {
            val updateDir = File(context.cacheDir, "apk_updates")
            if (updateDir.exists()) {
                updateDir.deleteRecursively()
            }
        } catch (_: Throwable) {
            // Safe ignore
        }
    }
}
