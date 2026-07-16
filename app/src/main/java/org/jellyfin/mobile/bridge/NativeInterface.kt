package org.jellyfin.mobile.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadManager
import org.jellyfin.mobile.downloads.DownloadProgressStore
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.settings.VideoPlayerType
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.Constants.EXTRA_ALBUM
import org.jellyfin.mobile.utils.Constants.EXTRA_ARTIST
import org.jellyfin.mobile.utils.Constants.EXTRA_CAN_SEEK
import org.jellyfin.mobile.utils.Constants.EXTRA_DURATION
import org.jellyfin.mobile.utils.Constants.EXTRA_IMAGE_URL
import org.jellyfin.mobile.utils.Constants.EXTRA_IS_LOCAL_PLAYER
import org.jellyfin.mobile.utils.Constants.EXTRA_IS_PAUSED
import org.jellyfin.mobile.utils.Constants.EXTRA_ITEM_ID
import org.jellyfin.mobile.utils.Constants.EXTRA_PLAYER_ACTION
import org.jellyfin.mobile.utils.Constants.EXTRA_POSITION
import org.jellyfin.mobile.utils.Constants.EXTRA_TITLE
import org.jellyfin.mobile.webapp.RemotePlayerService
import org.jellyfin.mobile.webapp.RemoteVolumeProvider
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import timber.log.Timber
import java.util.UUID

@Suppress("unused")
class NativeInterface(private val context: Context) : KoinComponent {
    private val activityEventHandler: ActivityEventHandler = get()
    private val remoteVolumeProvider: RemoteVolumeProvider by inject()
    private val deviceProfileBuilder: DeviceProfileBuilder by inject()

    @SuppressLint("HardwareIds")
    @JavascriptInterface
    fun getDeviceInformation(): String? = try {
        val apiClient: ApiClient = get()
        val deviceInfo = apiClient.deviceInfo
        val clientInfo = apiClient.clientInfo

        JSONObject().apply {
            put("deviceId", deviceInfo.id)
            // normalize the name by removing special characters
            // and making sure it's at least 1 character long
            // otherwise the webui will fail to send it to the server
            val name = AuthorizationHeaderBuilder.encodeParameterValue(deviceInfo.name).padStart(1)
            put("deviceName", name)
            put("appName", clientInfo.name)
            put("appVersion", clientInfo.version)
        }.toString()
    } catch (e: JSONException) {
        null
    }

    @JavascriptInterface
    fun getCodecCapabilities(): String = deviceProfileBuilder.getWebCodecCapabilitiesJson()

    @JavascriptInterface
    fun hasChromecast(): Boolean = BuildConfig.IS_PROPRIETARY

    @JavascriptInterface
    fun enableFullscreen(): Boolean {
        emitEvent(ActivityEvent.ChangeFullscreen(true))
        return true
    }

    @JavascriptInterface
    fun disableFullscreen(): Boolean {
        emitEvent(ActivityEvent.ChangeFullscreen(false))
        return true
    }

    @JavascriptInterface
    fun openUrl(uri: String): Boolean {
        emitEvent(ActivityEvent.OpenUrl(uri))
        return true
    }

    @JavascriptInterface
    fun updateMediaSession(args: String): Boolean {
        val options = try {
            JSONObject(args)
        } catch (e: JSONException) {
            Timber.e("updateMediaSession: %s", e.message)
            return false
        }
        val intent = Intent(context, RemotePlayerService::class.java).apply {
            action = Constants.ACTION_REPORT
            putExtra(EXTRA_PLAYER_ACTION, options.optString(EXTRA_PLAYER_ACTION))
            putExtra(EXTRA_ITEM_ID, options.optString(EXTRA_ITEM_ID))
            putExtra(EXTRA_TITLE, options.optString(EXTRA_TITLE))
            putExtra(EXTRA_ARTIST, options.optString(EXTRA_ARTIST))
            putExtra(EXTRA_ALBUM, options.optString(EXTRA_ALBUM))
            putExtra(EXTRA_IMAGE_URL, options.optString(EXTRA_IMAGE_URL))
            putExtra(EXTRA_POSITION, options.optLong(EXTRA_POSITION, PlaybackState.PLAYBACK_POSITION_UNKNOWN))
            putExtra(EXTRA_DURATION, options.optLong(EXTRA_DURATION))
            putExtra(EXTRA_CAN_SEEK, options.optBoolean(EXTRA_CAN_SEEK))
            putExtra(EXTRA_IS_LOCAL_PLAYER, options.optBoolean(EXTRA_IS_LOCAL_PLAYER, true))
            putExtra(EXTRA_IS_PAUSED, options.optBoolean(EXTRA_IS_PAUSED, true))
        }

        ContextCompat.startForegroundService(context, intent)

        // We may need to request bluetooth permission to react to bluetooth disconnect events
        activityEventHandler.emit(ActivityEvent.RequestBluetoothPermission)
        return true
    }

    @JavascriptInterface
    fun hideMediaSession(): Boolean {
        val intent = Intent(context, RemotePlayerService::class.java).apply {
            action = Constants.ACTION_REPORT
            putExtra(EXTRA_PLAYER_ACTION, "playbackstop")
        }
        context.startService(intent)
        return true
    }

    @JavascriptInterface
    fun updateVolumeLevel(value: Int) {
        remoteVolumeProvider.currentVolume = value
    }

    @JavascriptInterface
    fun downloadFiles(args: String): Boolean {
        try {
            val files = JSONArray(args)
            val itemIds = mutableSetOf<UUID>()

            repeat(files.length()) { index ->
                val file = files.getJSONObject(index)
                val itemId = file.getString("itemId").toUUID()

                itemIds.add(itemId)
            }

            emitEvent(ActivityEvent.DownloadItems(itemIds))
        } catch (e: JSONException) {
            Timber.e("Download failed: %s", e.message)
            return false
        }

        return true
    }

    @JavascriptInterface
    fun openDownloadManager() {
        emitEvent(ActivityEvent.OpenDownloads)
    }

    /**
     * Expose the current downloads (with live status/progress) to the web UI so it can render
     * them as an in-app page instead of a separate native screen.
     */
    @JavascriptInterface
    fun getDownloads(): String = try {
        val downloadDao: DownloadDao = get()
        val storageManager: StorageManager = get()
        val progressStore: DownloadProgressStore = get()
        val progress = progressStore.progress.value

        val downloads = runBlocking { downloadDao.getAllDownloadsWithFiles().first() }
        JSONArray().apply {
            downloads.forEach { downloadFiles ->
                put(
                    JSONObject().apply {
                        val item = downloadFiles.download.item
                        put("id", downloadFiles.download.id)
                        put("itemId", downloadFiles.download.itemId.toString())
                        put("name", downloadFiles.download.getDisplayName(context))
                        put("status", downloadFiles.download.status.name)
                        put("percent", progress[downloadFiles.download.id] ?: -1)
                        put("verified", storageManager.verify(downloadFiles))
                        put("type", item.type.name)
                        put("seriesId", item.seriesId?.toString().orEmpty())
                        put("seriesName", item.seriesName.orEmpty())
                        put("season", item.parentIndexNumber ?: -1)
                        put("episode", item.indexNumber ?: -1)
                        put("size", downloadFiles.files.sumOf { file -> file.size })
                    },
                )
            }
        }.toString()
    } catch (e: Exception) {
        Timber.e(e, "getDownloads failed")
        "[]"
    }

    /**
     * Re-queue a download that failed or is incomplete, resuming from the partial file.
     */
    @JavascriptInterface
    fun retryDownload(id: Long) {
        try {
            val downloadDao: DownloadDao = get()
            val downloadManager: DownloadManager = get()
            runBlocking {
                downloadDao.getDownload(id)?.let { download -> downloadManager.resume(download) }
            }
        } catch (e: Exception) {
            Timber.e(e, "retryDownload failed")
        }
    }

    /**
     * Delete one or more downloads (by download id) and their local files.
     */
    @JavascriptInterface
    fun deleteDownloads(idsJson: String) {
        try {
            val ids = JSONArray(idsJson)
            val downloadManager: DownloadManager = get()
            runBlocking {
                for (index in 0 until ids.length()) {
                    downloadManager.delete(ids.getLong(index), deleteFiles = true)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "deleteDownloads failed")
        }
    }

    /**
     * The configured video player type, so the web UI can route downloads to the same player
     * that online playback uses.
     */
    @JavascriptInterface
    fun getVideoPlayerType(): String = get<AppPreferences>().videoPlayerType

    /**
     * Play a downloaded item from local storage, honouring the configured video player type
     * (native player by default, or an external app such as VLC).
     */
    @JavascriptInterface
    fun playDownload(itemId: String) {
        val id = itemId.toUUIDOrNull() ?: return
        if (playDownloadExternally(id)) return

        val playOptions = PlayOptions(
            ids = listOf(id),
            mediaSourceId = id.toString(),
            startIndex = 0,
            startPosition = null,
            audioStreamIndex = null,
            subtitleStreamIndex = null,
            playFromDownloads = true,
        )
        emitEvent(ActivityEvent.LaunchNativePlayer(playOptions))
    }

    /**
     * @return true if the item was handed to an external player, false to fall back to the native one.
     */
    private fun playDownloadExternally(itemId: UUID): Boolean {
        val appPreferences: AppPreferences = get()
        if (appPreferences.videoPlayerType != VideoPlayerType.EXTERNAL_PLAYER) return false

        return try {
            val downloadDao: DownloadDao = get()
            val storageManager: StorageManager = get()
            runBlocking {
                val download = downloadDao.getDownloadByItemId(itemId) ?: return@runBlocking false
                val file = downloadDao.getFiles(download.id)
                    .find { downloadFile -> downloadFile.type == DownloadFileType.ITEM }
                    ?: return@runBlocking false
                val uri = storageManager.getShareableUri(file.uri) ?: return@runBlocking false

                emitEvent(
                    ActivityEvent.PlayDownloadExternally(uri.toString(), download.getDisplayName(context).orEmpty()),
                )
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to hand download to external player")
            false
        }
    }

    @JavascriptInterface
    fun openClientSettings() {
        emitEvent(ActivityEvent.OpenSettings)
    }

    @JavascriptInterface
    fun openServerSelection() {
        emitEvent(ActivityEvent.SelectServer)
    }

    @JavascriptInterface
    fun exitApp() {
        emitEvent(ActivityEvent.ExitApp)
    }

    @JavascriptInterface
    fun execCast(action: String, args: String) {
        emitEvent(ActivityEvent.CastMessage(action, JSONArray(args)))
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun emitEvent(event: ActivityEvent) {
        activityEventHandler.emit(event)
    }
}
