// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
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
        val json = JSONObject(URL(UPDATE_JSON_URL).readText())
        val versionCode = json.getInt("versionCode")
        if (versionCode <= BuildConfig.VERSION_CODE) return null
        return UpdateInfo(
            versionName = json.getString("versionName"),
            versionCode = versionCode,
            apkUrl = json.getString("apkUrl"),
        )
    }

    fun download(update: UpdateInfo): File {
        val dir = File(context.cacheDir, UPDATE_CACHE_DIR).apply { mkdirs() }
        val file = File(dir, "skytox-${update.versionName}-universal.apk")
        URL(update.apkUrl).openStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
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
        private const val UPDATE_CACHE_DIR = "updates"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val UPDATE_JSON_URL =
            "https://github.com/Mark84Hoppus/MarkandDiego.skyTox/releases/latest/download/skytox-update.json"
    }
}
