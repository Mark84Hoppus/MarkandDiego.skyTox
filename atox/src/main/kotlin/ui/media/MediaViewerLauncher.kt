// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import ltd.evilcorp.atox.R

object MediaViewerLauncher {
    private const val TAG = "MediaViewerLauncher"

    fun openInternal(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String,
        mode: MediaViewerMode,
    ) {
        context.startActivity(Intent(context, MediaViewerActivity::class.java).apply {
            putExtra(MediaViewerActivity.EXTRA_URI, uri.toString())
            putExtra(MediaViewerActivity.EXTRA_NAME, displayName)
            putExtra(MediaViewerActivity.EXTRA_MIME, mimeType)
            putExtra(MediaViewerActivity.EXTRA_MODE, mode.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    fun openExternal(context: Context, uri: Uri, displayName: String, mimeType: String) {
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            putExtra(Intent.EXTRA_TITLE, displayName)
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(openIntent, context.getString(R.string.open_with)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.mimetype_handler_not_found, mimeType), Toast.LENGTH_LONG).show()
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Unable to open $displayName", error)
            Toast.makeText(context, R.string.file_not_found, Toast.LENGTH_SHORT).show()
        } catch (error: SecurityException) {
            Log.e(TAG, "Permission lost while opening $displayName", error)
            Toast.makeText(context, R.string.file_not_found, Toast.LENGTH_SHORT).show()
        }
    }
}
