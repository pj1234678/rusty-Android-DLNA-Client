package com.example.rustydlnaclient

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import java.util.Locale
class VideoProgressManager(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("video_progress", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "VideoProgressManager"
        private const val MIN_PROGRESS_TO_SAVE = 1000L // Only save if watched for at least 30 seconds
        private const val COMPLETION_THRESHOLD = 0.95f // Consider video completed if 95% watched
        private const val MAX_CACHE_ENTRIES = 2000
    }

    // PERF: in-memory read cache. Row binds call getVideoProgress() on every
    // bind (scroll, full rebinds); without this each bind costs 3 synchronized
    // SharedPreferences lookups plus 3 key-string builds (hashCode over the
    // full URL each time). All writes go through this instance (write-through
    // below); cross-instance writes (player vs browser each own an instance)
    // are handled by invalidateCache(), called from MainActivity.onResume()
    // before any rebind, so reads can never stay stale across screens.
    private val cache = HashMap<String, VideoProgress?>()

    /** Drop all cached entries; next read reloads from SharedPreferences. */
    @Synchronized
    fun invalidateCache() {
        cache.clear()
    }

    fun saveVideoProgress(videoUrl: String, currentPosition: Long, duration: Long) {
        if (currentPosition < MIN_PROGRESS_TO_SAVE) {
            Log.d(TAG, "Progress too short to save: ${currentPosition}ms")
            return
        }

        val progressPercent = if (duration > 0) currentPosition.toFloat() / duration else 0f

        // If video is nearly complete, remove the progress entry
        if (progressPercent >= COMPLETION_THRESHOLD) {
            removeVideoProgress(videoUrl)
            Log.d(TAG, "Video completed, removing progress for: $videoUrl")
            return
        }

        val now = System.currentTimeMillis()
        sharedPreferences.edit {
            putLong(getProgressKey(videoUrl), currentPosition)
            putLong(getDurationKey(videoUrl), duration)
            putLong(getTimestampKey(videoUrl), now)
        }
        putCached(videoUrl, VideoProgress(position = currentPosition, duration = duration, timestamp = now, progressPercent = progressPercent))

        Log.d(TAG, "Saved progress for $videoUrl: ${currentPosition}ms / ${duration}ms (${String.format("%.1f", progressPercent * 100)}%)")
    }

    fun getVideoProgress(videoUrl: String): VideoProgress? {
        synchronized(cache) {
            if (cache.containsKey(videoUrl)) return cache[videoUrl]
        }
        val position = sharedPreferences.getLong(getProgressKey(videoUrl), -1L)
        val duration = sharedPreferences.getLong(getDurationKey(videoUrl), -1L)
        val timestamp = sharedPreferences.getLong(getTimestampKey(videoUrl), -1L)

        val progress = if (position >= 0 && duration > 0) {
            val progressPercent = position.toFloat() / duration
            VideoProgress(position, duration, timestamp, progressPercent)
        } else {
            null
        }
        putCached(videoUrl, progress)
        return progress
    }

    fun removeVideoProgress(videoUrl: String) {
        sharedPreferences.edit {
            remove(getProgressKey(videoUrl))
            remove(getDurationKey(videoUrl))
            remove(getTimestampKey(videoUrl))
        }
        putCached(videoUrl, null)

        Log.d(TAG, "Removed progress for: $videoUrl")
    }

    @Synchronized
    private fun putCached(videoUrl: String, progress: VideoProgress?) {
        if (cache.size >= MAX_CACHE_ENTRIES && !cache.containsKey(videoUrl)) {
            cache.clear()
        }
        cache[videoUrl] = progress
    }

    /**
     * Bulk-load keyed by the key-hash prefix. NOTE: keys store
     * `url.hashCode()`, which is one-way — the original code stripped the
     * suffix and re-looked-up by that hash-as-URL, so it always missed and
     * cleanup never deleted anything (unbounded SP growth). Grouping the raw
     * keys avoids per-entry lookups entirely: one `all` snapshot, zero
     * repeated reads.
     */
    private fun getAllVideoProgress(): Map<String, VideoProgress> {
        val allEntries = sharedPreferences.all
        val byHash = mutableMapOf<String, LongArray>()
        for ((key, value) in allEntries) {
            if (value !is Long) continue
            val hash = when {
                key.endsWith("_progress") -> key.dropLast("_progress".length)
                key.endsWith("_duration") -> key.dropLast("_duration".length)
                key.endsWith("_timestamp") -> key.dropLast("_timestamp".length)
                else -> continue
            }
            val slot = when {
                key.endsWith("_progress") -> 0
                key.endsWith("_duration") -> 1
                else -> 2
            }
            val arr = byHash.getOrPut(hash) { longArrayOf(-1L, -1L, -1L) }
            arr[slot] = value
        }
        val progressMap = mutableMapOf<String, VideoProgress>()
        for ((hash, arr) in byHash) {
            val position = arr[0]
            val duration = arr[1]
            if (position >= 0 && duration > 0) {
                progressMap[hash] = VideoProgress(position, duration, arr[2], position.toFloat() / duration)
            }
        }
        return progressMap
    }

    fun cleanupOldProgress(maxAgeMillis: Long = 30L * 24 * 60 * 60 * 1000) { // 30 days default
        val currentTime = System.currentTimeMillis()
        val allProgress = getAllVideoProgress()

        var cleanedCount = 0
        sharedPreferences.edit {
            for ((hash, progress) in allProgress) {
                if (currentTime - progress.timestamp > maxAgeMillis) {
                    remove("${hash}_progress")
                    remove("${hash}_duration")
                    remove("${hash}_timestamp")
                    cleanedCount++
                }
            }
        }
        if (cleanedCount > 0) {
            // Hash keys can't map back to URLs, so drop the whole read cache.
            invalidateCache()
            Log.d(TAG, "Cleaned up $cleanedCount old progress entries")
        }
    }

    private fun getProgressKey(videoUrl: String): String = "${videoUrl.hashCode()}_progress"
    private fun getDurationKey(videoUrl: String): String = "${videoUrl.hashCode()}_duration"
    private fun getTimestampKey(videoUrl: String): String = "${videoUrl.hashCode()}_timestamp"

    data class VideoProgress(
        val position: Long,
        val duration: Long,
        val timestamp: Long,
        val progressPercent: Float
    ) {
        fun getFormattedProgress(): String {
            return "${formatTime(position)} / ${formatTime(duration)} (${String.format(Locale.US, "%.0f", progressPercent * 100)}%)"
        }

        private fun formatTime(timeMs: Long): String {
            val totalSeconds = timeMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60

            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }
    }
}