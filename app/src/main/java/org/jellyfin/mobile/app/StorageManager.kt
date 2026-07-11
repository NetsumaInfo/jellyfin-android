package org.jellyfin.mobile.app

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.DownloadStatus
import timber.log.Timber

class StorageManager(
    private val context: Context,
    private val appPreferences: AppPreferences
) {
    val defaultStorageLocation
        get() = Environment.getExternalStorageDirectory().resolve(context.getString(R.string.app_name_short)).toUri()

    /**
     * The storage location used for downloads. If the user picked a custom folder (SAF) it is used,
     * otherwise we fall back to a dedicated, app-specific directory that requires no permission and
     * is created automatically.
     */
    fun getStorageLocation(): DocumentFile? {
        val customLocation = appPreferences.storageLocation?.toUri()?.let {
            DocumentFile.fromTreeUri(context, it)
        }
        return customLocation ?: getAppDownloadDirectory()
    }

    /**
     * App-specific download directory under external files dir (no permission required, auto-created).
     * Falls back to internal files dir if external storage is unavailable.
     */
    private fun getAppDownloadDirectory(): DocumentFile? {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val downloadDir = baseDir.resolve(DOWNLOAD_DIRECTORY)
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            Timber.e("Failed to create app download directory at %s", downloadDir.absolutePath)
            return null
        }
        return DocumentFile.fromFile(downloadDir).also(::ensureNoMedia)
    }

    fun isStorageLocationAccessible(): Boolean {
        val documentFile = getStorageLocation()
        return documentFile != null && documentFile.exists() && documentFile.canWrite()
    }

    fun changeStorageLocation(location: Uri): Boolean {
        if (appPreferences.storageLocation?.toUri() == location) return true

        return runCatching {
            context.contentResolver.takePersistableUriPermission(
                location,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )

            appPreferences.storageLocation = location.toString()
            getStorageLocation()?.let(::ensureNoMedia)
        }.onFailure { err ->
            Timber.e(err, "Failed to change storage location to $location")
        }.isFailure
    }

    fun verify(download: DownloadFiles): Boolean {
        if (download.files.isEmpty()) return false

        for (file in download.files) {
            if (file.status != DownloadStatus.DOWNLOADED) return false
            if (!fileExists(file.uri) || fileLength(file.uri) != file.size) {
                return false
            }
        }

        return true
    }

    /**
     * Length of a downloaded file. Works for both SAF (content://) and app-specific (file://) uris.
     * [DocumentFile.fromSingleUri] only supports content uris, so file uris are handled directly.
     */
    fun fileLength(uri: Uri): Long = if (uri.scheme == ContentResolver.SCHEME_FILE) {
        runCatching { uri.toFile().length() }.getOrDefault(0L)
    } else {
        DocumentFile.fromSingleUri(context, uri)?.length() ?: 0L
    }

    /**
     * Existence of a downloaded file. Works for both SAF (content://) and app-specific (file://) uris.
     */
    fun fileExists(uri: Uri): Boolean = if (uri.scheme == ContentResolver.SCHEME_FILE) {
        runCatching { uri.toFile().exists() }.getOrDefault(false)
    } else {
        DocumentFile.fromSingleUri(context, uri)?.exists() == true
    }

    private fun ensureNoMedia(documentFile: DocumentFile) {
        if (documentFile.findFile(NOMEDIA_FILE) == null) {
            documentFile.createFile("", NOMEDIA_FILE)
        }
    }

    companion object {
        const val NOMEDIA_FILE = ".nomedia"
        const val DOWNLOAD_DIRECTORY = "Downloads"
    }
}
