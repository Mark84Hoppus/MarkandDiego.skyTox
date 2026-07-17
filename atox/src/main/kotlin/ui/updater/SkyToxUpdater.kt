// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import ltd.evilcorp.atox.BuildConfig
import org.json.JSONObject

class SkyToxUpdater(private val context: Context) {
    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun check(): UpdateInfo? {
        readUpdateJson()?.let { return it }
        return readLatestRelease()
    }

    private fun readUpdateJson(): UpdateInfo? = runCatching {
        val json = JSONObject(readUrl(UPDATE_JSON_URL))
        val versionCode = json.getInt("versionCode")
        if (versionCode <= BuildConfig.VERSION_CODE) return null
        UpdateInfo(
            versionName = json.getString("versionName"),
            versionCode = versionCode,
            apkUrl = json.getString("apkUrl"),
        )
    }.getOrNull()

    private fun readLatestRelease(): UpdateInfo? = runCatching {
        val json = JSONObject(readUrl(GITHUB_RELEASE_URL))
        val versionName = json.getString("tag_name").removePrefix("v")
        if (!isNewerVersion(versionName, BuildConfig.VERSION_NAME)) return null

        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (name.contains("universal", ignoreCase = true) &&
                name.endsWith(".apk", ignoreCase = true)
            ) {
                return UpdateInfo(
                    versionName = versionName,
                    versionCode = BuildConfig.VERSION_CODE + 1,
                    apkUrl = asset.getString("browser_download_url"),
                )
            }
        }
        null
    }.getOrNull()

    fun download(update: UpdateInfo, onProgress: (Int) -> Unit, isCancelled: () -> Boolean): File {
        val dir = File(
            Environment.getExternalStorageDirectory(),
            "$SKYTOX_ROOT_DIR/$UPDATE_APP_DIR",
        ).apply { mkdirs() }
        val file = File(dir, "skytox-${update.versionName}-universal.apk")
        val connection = URL(update.apkUrl).openConnection()
        val total = connection.contentLengthLong
        connection.getInputStream().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    if (isCancelled()) {
                        file.delete()
                        throw InterruptedException("cancelled")
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total > 0L) {
                        onProgress(((copied * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
        onProgress(100)
        return file
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
    )

    companion object {
        private const val SKYTOX_ROOT_DIR = "skyTox files"
        private const val UPDATE_APP_DIR = "skyTox app"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val UPDATE_JSON_URL =
            "https://github.com/Mark84Hoppus/MarkandDiego.skyTox/releases/latest/download/skytox-update.json"
        private const val GITHUB_RELEASE_URL =
            "https://api.github.com/repos/Mark84Hoppus/MarkandDiego.skyTox/releases/latest"
    }
}

private fun readUrl(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.setRequestProperty("User-Agent", "skyTox-updater")
    connection.connectTimeout = 15_000
    connection.readTimeout = 30_000
    return connection.inputStream.bufferedReader().use { it.readText() }
}

private fun isNewerVersion(remote: String, local: String): Boolean {
    val remoteParts = remote.split('.', ',', '-').mapNotNull { it.toIntOrNull() }
    val localParts = local.split('.', ',', '-').mapNotNull { it.toIntOrNull() }
    val max = maxOf(remoteParts.size, localParts.size)
    for (i in 0 until max) {
        val r = remoteParts.getOrElse(i) { 0 }
        val l = localParts.getOrElse(i) { 0 }
        if (r != l) return r > l
    }
    return false
}
