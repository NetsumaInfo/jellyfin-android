package org.jellyfin.mobile.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory store for live download progress, keyed by download id.
 *
 * Progress is intentionally kept out of the database to avoid a write per downloaded chunk.
 * The UI observes [progress] to render a determinate progress bar while a download is running.
 */
class DownloadProgressStore {
    private val _progress = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val progress: StateFlow<Map<Long, Int>> = _progress.asStateFlow()

    /**
     * Set the current progress (0-100) for the given download.
     */
    fun update(id: Long, percent: Int) {
        _progress.update { current ->
            if (current[id] == percent) current else current + (id to percent)
        }
    }

    /**
     * Remove tracking for the given download (completed, cancelled or failed).
     */
    fun clear(id: Long) {
        _progress.update { current ->
            if (current.containsKey(id)) current - id else current
        }
    }
}
