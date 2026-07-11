package org.jellyfin.mobile.downloads

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import java.io.IOException

private enum class ProcessResult {
    SUCCESS,
    TRANSIENT_FAILURE,
    PERMANENT_FAILURE,
    CANCELLED,
}

class DownloadQueue(
    private val context: Context,
    private val apiClientController: ApiClientController,
    private val downloadDao: DownloadDao,
    private val downloadNotificationManager: DownloadNotificationManager,
    private val storageManager: StorageManager,
    private val progressStore: DownloadProgressStore,
    okHttpClient: OkHttpClient,
) {
    private companion object {
        const val PERCENT_MAX = 100
    }

    private data class QueuedFile(val file: DownloadFileEntity, val remoteUri: Uri)

    private val _downloader = FileDownloader(okHttpClient)
    private val _downloads = mutableListOf<DownloadFiles>()

    suspend fun prepare(): Boolean {
        val queuedDownloads = downloadDao.getQueuedDownloads()
        _downloads.clear()
        _downloads.addAll(queuedDownloads)
        return _downloads.any()
    }

    /**
     * Process all queued downloads. A single item failing no longer aborts the whole queue.
     *
     * @return true if one or more downloads failed transiently (e.g. network loss) and the
     * worker should be rescheduled to retry them later.
     */
    suspend fun process(): Boolean {
        val failedThisRun = mutableSetOf<Long>()
        var hasPendingRetries = false

        while (true) {
            prepare()
            // Skip items that already failed transiently this run to avoid a hot retry loop;
            // they stay QUEUED and are retried on the next (back-off delayed) worker run.
            val pending = _downloads.filter { it.download.id !in failedThisRun }
            if (pending.isEmpty()) break

            for (downloadWithFiles in pending) {
                when (process(downloadWithFiles)) {
                    ProcessResult.SUCCESS,
                    ProcessResult.PERMANENT_FAILURE,
                    -> Unit
                    ProcessResult.TRANSIENT_FAILURE -> {
                        failedThisRun += downloadWithFiles.download.id
                        hasPendingRetries = true
                    }
                    ProcessResult.CANCELLED -> return hasPendingRetries
                }
            }
        }

        return hasPendingRetries
    }

    private suspend fun process(downloadWithFiles: DownloadFiles): ProcessResult {
        // Mark as downloading
        downloadDao.update(downloadWithFiles.download.copy(status = DownloadStatus.DOWNLOADING))
        val api = apiClientController.getApiClient(downloadWithFiles.download.serverId, downloadWithFiles.download.userId)

        val downloadId = downloadWithFiles.download.id
        try {
            val queuedFiles = prepareFiles(api, downloadWithFiles)

            val notificationProgressCallback = downloadNotificationManager.downloadFile(
                downloadId,
                downloadWithFiles.download.getDisplayName(context).orEmpty(),
            )

            // Forward progress both to the notification and the in-app progress store
            val progressCallback = FileDownloader.ProgressCallback { downloaded, total ->
                notificationProgressCallback.onProgress(downloaded, total)
                val percent = if (total > 0) {
                    (downloaded.toFloat() / total.toFloat() * PERCENT_MAX).toInt().coerceIn(0, PERCENT_MAX)
                } else {
                    0
                }
                progressStore.update(downloadId, percent)
            }

            for (queuedFile in queuedFiles) {
                download(api, queuedFile, progressCallback)
            }

            notificationProgressCallback.onEnd()
            progressStore.clear(downloadId)
            downloadDao.update(downloadWithFiles.download.copy(status = DownloadStatus.DOWNLOADED))
            return ProcessResult.SUCCESS
        } catch (_: CancellationException) {
            // Worker was stopped: keep the item queued so it resumes next time.
            progressStore.clear(downloadId)
            downloadDao.update(downloadWithFiles.download.copy(status = DownloadStatus.QUEUED))
            return ProcessResult.CANCELLED
        } catch (error: IOException) {
            // Network / IO problem: keep queued and retry later (partial file resumes via HTTP Range).
            Timber.w(error, "Transient download failure for id %d, will retry", downloadId)
            progressStore.clear(downloadId)
            downloadDao.update(downloadWithFiles.download.copy(status = DownloadStatus.QUEUED))
            return ProcessResult.TRANSIENT_FAILURE
        } catch (error: Throwable) {
            // Unexpected/permanent problem: mark as errored but keep processing the rest of the queue.
            Timber.e(error, "Permanent download failure for id %d", downloadId)
            progressStore.clear(downloadId)
            downloadDao.update(downloadWithFiles.download.copy(status = DownloadStatus.ERROR))
            return ProcessResult.PERMANENT_FAILURE
        }
    }

    private suspend fun download(
        api: ApiClient,
        queuedFile: QueuedFile,
        progressCallback: FileDownloader.ProgressCallback,
    ) {
        val (file, remoteUri) = queuedFile

        // Verify downloaded files and skip if valid
        if (file.status == DownloadStatus.DOWNLOADED && file.size > 0) {
            if (storageManager.fileExists(file.uri) && storageManager.fileLength(file.uri) == file.size) return
        }

        val fileDescriptor = context.contentResolver.openFileDescriptor(file.uri, "rw")
            ?: error("Unable to open file descriptor for ${file.fileName}")

        downloadDao.updateFile(file.copy(status = DownloadStatus.DOWNLOADING))

        try {
            _downloader.downloadAndSave(
                api = api,
                from = remoteUri,
                to = fileDescriptor,
                progressCallback = progressCallback,
            )

            // Update file record with final size and status
            downloadDao.updateFile(
                file.copy(
                    size = storageManager.fileLength(file.uri),
                    status = DownloadStatus.DOWNLOADED,
                ),
            )
        } catch (e: Exception) {
            downloadDao.updateFile(file.copy(status = DownloadStatus.ERROR))
            throw e
        }
    }

    private suspend fun prepareFiles(api: ApiClient, downloadWithFiles: DownloadFiles): List<QueuedFile> {
        val storageLocation = storageManager.getStorageLocation()
        val itemLocation = storageLocation?.findFile(downloadWithFiles.download.path)
            ?: storageLocation?.createDirectory(downloadWithFiles.download.path)
            ?: error("Unable to find or create folder ${downloadWithFiles.download.path}")

        return buildList {
            // Add image as first item so it can be shown in UI during downloads
            preparePrimaryImageFile(api, downloadWithFiles, itemLocation)?.let(::add)

            // Add main item second as it is (often) the largest and important file
            prepareMainFile(api, downloadWithFiles, itemLocation).let(::add)
        }
    }

    private suspend fun prepareMainFile(
        api: ApiClient,
        downloadWithFiles: DownloadFiles,
        itemLocation: DocumentFile,
    ) = QueuedFile(
        file = createOrUpdateFile(
            filter = { it.type == DownloadFileType.ITEM },
            downloadWithFiles = downloadWithFiles,
            itemLocation = itemLocation,
            type = DownloadFileType.ITEM,
            fileName = downloadWithFiles.download.item.path?.replace(Regex("^.*[\\\\/]"), "") ?: error("Missing item path"),
        ),
        remoteUri = api.libraryApi.getDownloadUrl(downloadWithFiles.download.item.id).toUri()
    )

    private suspend fun preparePrimaryImageFile(
        api: ApiClient,
        downloadWithFiles: DownloadFiles,
        itemLocation: DocumentFile
    ): QueuedFile? = downloadWithFiles.download.item.imageTags?.get(ImageType.PRIMARY)?.let { imageTag ->
        QueuedFile(
            file = createOrUpdateFile(
                filter = { it.type == DownloadFileType.IMAGE_PRIMARY },
                downloadWithFiles = downloadWithFiles,
                itemLocation = itemLocation,
                type = DownloadFileType.IMAGE_PRIMARY,
                fileName = "primary.webp",
            ),
            remoteUri = api.imageApi.getItemImageUrl(
                itemId = downloadWithFiles.download.item.id,
                imageType = ImageType.PRIMARY,
                tag = imageTag,
                format = ImageFormat.WEBP,
            ).toUri()
        )
    }

    private suspend fun createOrUpdateFile(
        filter: (DownloadFileEntity) -> Boolean,
        downloadWithFiles: DownloadFiles,
        itemLocation: DocumentFile,
        type: DownloadFileType,
        fileName: String,
    ): DownloadFileEntity {
        var downloadFile = downloadWithFiles.files.firstOrNull(filter)

        val file = itemLocation.findFile(fileName)
            ?: itemLocation.createFile("", fileName)
            ?: error("Unable to create file $fileName")

        if (downloadFile != null) {
            downloadFile = downloadFile.copy(
                type = type,
                size = 0L,
                fileName = fileName,
                uri = file.uri,
                status = DownloadStatus.QUEUED,
            )
            downloadDao.updateFile(downloadFile)
            return downloadFile
        } else {
            downloadFile = DownloadFileEntity(
                downloadId = downloadWithFiles.download.id,
                type = type,
                size = 0L,
                fileName = fileName,
                uri = file.uri,
                status = DownloadStatus.QUEUED,
            )

            val id = downloadDao.insertFile(downloadFile)
            downloadFile = downloadFile.copy(id = id)
            return downloadFile
        }
    }
}
