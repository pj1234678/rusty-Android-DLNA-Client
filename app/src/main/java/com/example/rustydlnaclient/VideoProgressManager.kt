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

        sharedPreferences.edit {
            putLong(getProgressKey(videoUrl), currentPosition)
            putLong(getDurationKey(videoUrl), duration)
            putLong(getTimestampKey(videoUrl), System.currentTimeMillis())
        }

        Log.d(TAG, "Saved progress for $videoUrl: ${currentPosition}ms / ${duration}ms (${String.format("%.1f", progressPercent * 100)}%)")
    }

    fun getVideoProgress(videoUrl: String): VideoProgress? {
        val position = sharedPreferences.getLong(getProgressKey(videoUrl), -1L)
        val duration = sharedPreferences.getLong(getDurationKey(videoUrl), -1L)
        val timestamp = sharedPreferences.getLong(getTimestampKey(videoUrl), -1L)

        return if (position >= 0 && duration > 0) {
            val progressPercent = position.toFloat() / duration
            VideoProgress(position, duration, timestamp, progressPercent)
        } else {
            null
        }
    }

    fun removeVideoProgress(videoUrl: String) {
        sharedPreferences.edit {
            remove(getProgressKey(videoUrl))
            remove(getDurationKey(videoUrl))
            remove(getTimestampKey(videoUrl))
        }

        Log.d(TAG, "Removed progress for: $videoUrl")
    }

    private fun getAllVideoProgress(): Map<String, VideoProgress> {
        val allEntries = sharedPreferences.all
        val progressMap = mutableMapOf<String, VideoProgress>()

        val progressEntries = allEntries.keys.filter { it.endsWith("_progress") }

        for (progressKey in progressEntries) {
            val videoUrl = progressKey.removeSuffix("_progress")
            val progress = getVideoProgress(videoUrl)
            if (progress != null) {
                progressMap[videoUrl] = progress
            }
        }

        return progressMap
    }

    fun cleanupOldProgress(maxAgeMillis: Long = 30L * 24 * 60 * 60 * 1000) { // 30 days default
        val currentTime = System.currentTimeMillis()
        val allProgress = getAllVideoProgress()

        var cleanedCount = 0
        for ((videoUrl, progress) in allProgress) {
            if (currentTime - progress.timestamp > maxAgeMillis) {
                removeVideoProgress(videoUrl)
                cleanedCount++
            }
        }

        if (cleanedCount > 0) {
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