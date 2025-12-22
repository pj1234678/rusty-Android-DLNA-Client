package com.example.rustydlnaclient

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import kotlin.math.abs
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.common.ForwardingPlayer
import androidx.media3.session.MediaSession
import com.example.rustydlnaclient.MainActivity.Companion.EXTRA_START_INDEX
import com.example.rustydlnaclient.MainActivity.Companion.EXTRA_VIDEO_TITLES
import com.example.rustydlnaclient.MainActivity.Companion.EXTRA_VIDEO_URLS
import java.text.SimpleDateFormat
import java.util.*

class VideoPlayerActivity : ComponentActivity() {

    // Core components - lazy initialization where possible
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleOverlay: android.widget.TextView
    private lateinit var clockOverlay: android.widget.TextView

    // Optimized handlers - reuse single handler
    private val mainHandler = Handler(Looper.getMainLooper())

    // Optimized runnables - reuse objects
    private var titleFadeRunnable: Runnable? = null
    private var clockUpdateRunnable: Runnable? = null
    private var progressSaveRunnable: Runnable? = null
    private var backgroundCheckRunnable: Runnable? = null

    // Constants - moved to companion for memory efficiency
    companion object {
        private const val TAG = "VideoPlayerActivity"
        private const val TITLE_FADE_DELAY_MS = 3000L
        private const val TITLE_FADE_DURATION_MS = 500L
        private const val CLOCK_UPDATE_INTERVAL_MS = 1000L
        private const val PROGRESS_SAVE_INTERVAL_MS = 10000L
        private const val BACKGROUND_CHECK_DELAY_MS = 500L
        private const val POSITION_THRESHOLD_MS = 5000L
        private const val SEMI_TRANSPARENT_BLACK = 0x66000000
        private const val OVERLAY_MARGIN = 50
        private const val OVERLAY_PADDING_H = 24
        private const val OVERLAY_PADDING_V = 16
        private const val CLOCK_PADDING = 16
        private const val CLOCK_PADDING_V = 12
    }

    // Cached objects to reduce allocations
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val calendar = Calendar.getInstance()
    private val endTimeCalendar = Calendar.getInstance()

    // Progress tracking - optimized
    private lateinit var progressManager: VideoProgressManager
    private var videoUrls: List<String> = emptyList()
    private var videoTitles: List<String> = emptyList()
    private var isInitialLoad = true

    // State flags - optimized
    private var isAppInBackground = false
    private var isDestroyed = false
    private var isPaused = false

    // Optimized lifecycle observer with reduced allocations
    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            super.onStart(owner)
            if (!isDestroyed) {
                isAppInBackground = false
                Log.d(TAG, "App foreground")
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            super.onStop(owner)
            if (!isDestroyed) {
                isAppInBackground = true
                Log.d(TAG, "App background")
                saveCurrentProgress()
                finish()
            }
        }
    }

    // Optimized player listener - single instance
    private val playerListener = object : androidx.media3.common.Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            if (isDestroyed) return

            logMediaTransition(reason)

            mediaItem?.mediaMetadata?.title?.let { newTitle ->
                titleOverlay.text = newTitle
                showOverlays()
                startTitleFadeTimer()
            }

            if (reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                resumeFromSavedPosition()
            }

            startProgressSaving()
            updateClockDisplay()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            if (isDestroyed) return

            Log.d(TAG, "State: ${getPlaybackStateString(playbackState)}")

            when (playbackState) {
                androidx.media3.common.Player.STATE_READY -> {
                    if (isInitialLoad) {
                        resumeFromSavedPosition()
                        isInitialLoad = false
                    }
                    startProgressSaving()
                    updateClockDisplay()
                }
                androidx.media3.common.Player.STATE_ENDED -> {
                    saveCurrentProgress()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            if (isDestroyed) return

            if (isPlaying) {
                startProgressSaving()
                startClockUpdates()
            } else {
                stopProgressSaving()
                saveCurrentProgress()
                stopClockUpdates()
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Early exit optimization
        videoUrls = intent.getStringArrayListExtra(EXTRA_VIDEO_URLS) ?: emptyList()
        if (videoUrls.isEmpty()) {
            Log.w(TAG, "No URLs provided")
            finish()
            return
        }

        // Single setup call
        setupActivity()
        setupUI()
        setupPlayer()
    }

    private fun setupActivity() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        progressManager = VideoProgressManager(this)
        progressManager.cleanupOldProgress()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoTitles = intent.getStringArrayListExtra(EXTRA_VIDEO_TITLES) ?: emptyList()
    }

    @UnstableApi
    private fun setupUI() {
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
        val rootLayout = FrameLayout(this)

        // Optimized PlayerView creation
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = true
            controllerShowTimeoutMs = 3000
            setBackgroundColor(android.graphics.Color.BLACK)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
                }
            })
        }

        // Optimized overlay creation
        titleOverlay = createTitleOverlay(startIndex)
        clockOverlay = createClockOverlay()

        // Single touch listener for all interactions
        rootLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleUserInteraction()
            }
            false
        }

        rootLayout.addView(playerView)
        rootLayout.addView(titleOverlay)
        rootLayout.addView(clockOverlay)
        setContentView(rootLayout)
    }

    private fun createTitleOverlay(startIndex: Int): android.widget.TextView {
        return android.widget.TextView(this).apply {
            text = videoTitles.getOrNull(startIndex) ?: "Video"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(SEMI_TRANSPARENT_BLACK)
            setPadding(OVERLAY_PADDING_H, OVERLAY_PADDING_V, OVERLAY_PADDING_H, OVERLAY_PADDING_V)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = OVERLAY_MARGIN
                marginStart = OVERLAY_MARGIN
            }
            alpha = 0f  // Start invisible
            visibility = View.GONE  // Start hidden
        }
    }

    private fun createClockOverlay(): android.widget.TextView {
        return android.widget.TextView(this).apply {
            text = getCurrentTimeAndEndTime()
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(SEMI_TRANSPARENT_BLACK)
            setPadding(CLOCK_PADDING, CLOCK_PADDING_V, CLOCK_PADDING, CLOCK_PADDING_V)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = OVERLAY_MARGIN
                marginEnd = OVERLAY_MARGIN
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
            }
            alpha = 0f  // Start invisible
            visibility = View.GONE  // Start hidden
        }
    }

    @OptIn(UnstableApi::class)
    private fun setupPlayer() {
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            val mediaItems = videoUrls.mapIndexed { index, url ->
                MediaItem.Builder()
                    .setUri(url)
                    .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(videoTitles.getOrNull(index)).build())
                    .build()
            }
            setMediaItems(mediaItems, startIndex, 0L)
            prepare()
            playWhenReady = true
            addListener(playerListener)
        }

        val forwardingPlayer = setupMediaSessionWithCustomControls()
        playerView.player = forwardingPlayer ?: exoPlayer

        // Start clock updates immediately, but don't show overlays at startup
        startClockUpdates()
    }

    // Optimized time calculation with object reuse
    private fun getCurrentTimeAndEndTime(): String {
        calendar.timeInMillis = System.currentTimeMillis()
        val currentTimeStr = timeFormat.format(calendar.time)

        val player = exoPlayer
        if (player != null && player.duration > 0 && player.currentPosition >= 0) {
            val remainingTimeMs = player.duration - player.currentPosition
            endTimeCalendar.timeInMillis = System.currentTimeMillis() + remainingTimeMs
            val endTimeStr = timeFormat.format(endTimeCalendar.time)
            return "$currentTimeStr\nEnds: $endTimeStr"
        }

        return currentTimeStr
    }

    private fun updateClockDisplay() {
        if (!isDestroyed) {
            clockOverlay.text = getCurrentTimeAndEndTime()
        }
    }

    // Optimized timer management - single handler, reused runnables
    private fun startClockUpdates() {
        stopClockUpdates()
        if (clockUpdateRunnable == null) {
            clockUpdateRunnable = object : Runnable {
                override fun run() {
                    if (!isDestroyed) {
                        updateClockDisplay()
                        mainHandler.postDelayed(this, CLOCK_UPDATE_INTERVAL_MS)
                    }
                }
            }
        }
        mainHandler.post(clockUpdateRunnable!!)
    }

    private fun stopClockUpdates() {
        clockUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    @OptIn(UnstableApi::class)
    private fun setupMediaSessionWithCustomControls(): ForwardingPlayer? {
        return exoPlayer?.let { player ->
            val forwardingPlayer = object : ForwardingPlayer(player) {
                override fun seekToPrevious() {
                    getCurrentVideoUrl()?.let { progressManager.removeVideoProgress(it) }
                    super.seekToPrevious()
                }

                override fun seekToPreviousMediaItem() {
                    getCurrentVideoUrl()?.let { progressManager.removeVideoProgress(it) }
                    super.seekToPreviousMediaItem()
                }
            }

            val sessionId = "VideoPlayerActivityMediaSession_${System.currentTimeMillis()}"
            mediaSession = MediaSession.Builder(this, forwardingPlayer)
                .setId(sessionId)
                .build()

            forwardingPlayer
        }
    }

    private fun getCurrentVideoUrl(): String? {
        val currentIndex = exoPlayer?.currentMediaItemIndex ?: return null
        return videoUrls.getOrNull(currentIndex)
    }

    private fun resumeFromSavedPosition() {
        val currentVideoUrl = getCurrentVideoUrl() ?: return
        val progress = progressManager.getVideoProgress(currentVideoUrl) ?: return
        val player = exoPlayer ?: return

        val currentPosition = player.currentPosition
        val savedPosition = progress.position

        if (abs(savedPosition - currentPosition) > POSITION_THRESHOLD_MS) {
            player.seekTo(savedPosition)
        }
    }

    private fun saveCurrentProgress() {
        val player = exoPlayer ?: return
        val currentVideoUrl = getCurrentVideoUrl() ?: return
        val currentPosition = player.currentPosition
        val duration = player.duration

        if (duration > 0 && currentPosition > 0) {
            progressManager.saveVideoProgress(currentVideoUrl, currentPosition, duration)
        }
    }

    // Optimized progress saving with reused runnable
    private fun startProgressSaving() {
        stopProgressSaving()
        if (progressSaveRunnable == null) {
            progressSaveRunnable = object : Runnable {
                override fun run() {
                    if (!isDestroyed) {
                        saveCurrentProgress()
                        mainHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS)
                    }
                }
            }
        }
        mainHandler.post(progressSaveRunnable!!)
    }

    private fun stopProgressSaving() {
        progressSaveRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    // Optimized user interaction handling
    private fun handleUserInteraction() {
        if (isDestroyed) return
        showOverlays()
        startTitleFadeTimer()
    }

    private fun showOverlays() {
        showOverlay(titleOverlay)
        showOverlay(clockOverlay)
    }

    private fun showOverlay(overlay: android.widget.TextView) {
        if (overlay.alpha < 1f || overlay.visibility != View.VISIBLE) {
            overlay.visibility = View.VISIBLE
            overlay.animate()
                .alpha(1f)
                .setDuration(TITLE_FADE_DURATION_MS / 2)
                .setListener(null)
                .start()
        }
    }

    private fun hideOverlays() {
        hideOverlay(titleOverlay)
        hideOverlay(clockOverlay)
    }

    private fun hideOverlay(overlay: android.widget.TextView) {
        overlay.animate()
            .alpha(0f)
            .setDuration(TITLE_FADE_DURATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isDestroyed) overlay.visibility = View.GONE
                }
            })
            .start()
    }

    // Optimized fade timer with reused runnable
    private fun startTitleFadeTimer() {
        titleFadeRunnable?.let { mainHandler.removeCallbacks(it) }
        if (titleFadeRunnable == null) {
            titleFadeRunnable = Runnable { if (!isDestroyed) hideOverlays() }
        }
        mainHandler.postDelayed(titleFadeRunnable!!, TITLE_FADE_DELAY_MS)
    }

    private fun stopTitleFadeTimer() {
        titleFadeRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    // Optimized logging methods
    private fun logMediaTransition(reason: Int) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            val currentUrl = getCurrentVideoUrl()
            val currentIndex = exoPlayer?.currentMediaItemIndex ?: -1
            Log.d(TAG, "Media transition - URL (index $currentIndex): $currentUrl, reason: ${getTransitionReasonString(reason)}")
        }
    }

    private fun getTransitionReasonString(reason: Int): String {
        return when (reason) {
            androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
            androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
            androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
            androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
            else -> "UNKNOWN($reason)"
        }
    }

    private fun getPlaybackStateString(state: Int): String {
        return when (state) {
            androidx.media3.common.Player.STATE_IDLE -> "IDLE"
            androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
            androidx.media3.common.Player.STATE_READY -> "READY"
            androidx.media3.common.Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        handleUserInteraction()
    }

    override fun onBackPressed() {
        // Disable back button animation by finishing immediately
        saveCurrentProgress()
        super.onBackPressed()
        overridePendingTransition(0, 0) // No animation
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0) // No animation when finishing
    }

    override fun onDestroy() {
        super.onDestroy()
        isDestroyed = true

        // Cleanup in optimal order
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)
        saveCurrentProgress()

        // Stop all timers with single handler cleanup
        mainHandler.removeCallbacksAndMessages(null)

        // Cancel animations
        titleOverlay.animate().cancel()
        clockOverlay.animate().cancel()

        // Release media components
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null

        // Clear flags
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        isPaused = true

        exoPlayer?.pause()
        saveCurrentProgress()
        stopTitleFadeTimer()
        stopProgressSaving()
        stopClockUpdates()

        // Optimized background check
        if (backgroundCheckRunnable == null) {
            backgroundCheckRunnable = Runnable {
                if (isAppInBackground && !isDestroyed) finish()
            }
        }
        mainHandler.postDelayed(backgroundCheckRunnable!!, BACKGROUND_CHECK_DELAY_MS)
    }

    override fun onResume() {
        super.onResume()
        isPaused = false

        backgroundCheckRunnable?.let { mainHandler.removeCallbacks(it) }

        exoPlayer?.play()
        showOverlays()
        startTitleFadeTimer()
        startProgressSaving()
        startClockUpdates()
    }

    override fun onStop() {
        super.onStop()
        saveCurrentProgress()
        if (!isFinishing && !isDestroyed) finish()
    }
}