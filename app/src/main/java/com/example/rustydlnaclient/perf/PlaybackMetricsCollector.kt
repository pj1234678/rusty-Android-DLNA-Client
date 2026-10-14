package com.example.rustydlnaclient.perf

import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaLoadData

/**
 * Singleton decoder/buffer metrics collector.
 *
 * Wired as an [AnalyticsListener] (already on the classpath). All timing uses
 * [SystemClock.elapsedRealtime] deltas owned by this class — never
 * AnalyticsListener.EventTime internals — so it is robust across
 * Media3 minor versions.
 *
 * Functional behavior is untouched: this class only observes and logs.
 */
object PlaybackMetricsCollector : AnalyticsListener {

    // ---- per-item state (reset on every item transition) ----
    @Volatile
    private var itemIndex: Int = 0

    @Volatile
    private var uriHash: Int = 0

    @Volatile
    private var firstReadyElapsed: Long = -1L

    @Volatile
    private var firstPlayingElapsed: Long = -1L

    @Volatile
    private var rebufferCount: Int = 0

    @Volatile
    private var rebufferTotalMs: Long = 0L

    @Volatile
    private var rebufferStartElapsed: Long = -1L

    @Volatile
    private var droppedFrames: Int = 0

    @Volatile
    private var decoderName: String? = null

    @Volatile
    private var decoderInitMs: Long = -1L

    @Volatile
    private var codec: String? = null

    @Volatile
    private var container: String? = null

    @Volatile
    private var bitrate: Int = -1

    @Volatile
    private var everReady: Boolean = false

    /** Transition moment (setup or playlist advance); gap base for any Next. */
    @Volatile
    private var transitionElapsed: Long = -1L

    /** Items seen since process start; first one has no gap to measure. */
    @Volatile
    private var itemCount: Int = 0

    /** Called from the Activity's onMediaItemTransition (source of truth). */
    @Synchronized
    fun newItem(index: Int, url: String?) {
        itemCount++
        resetForItem(index, url)
    }

    /**
     * Called once per player setup for the start item. Resets the gap clock
     * so a fresh player never reports a transition gap (always -1).
     */
    @Synchronized
    fun firstItem(index: Int, url: String?) {
        itemCount = 1
        resetForItem(index, url)
    }

    private fun resetForItem(index: Int, url: String?) {
        transitionElapsed = SystemClock.elapsedRealtime()
        itemIndex = index
        uriHash = url?.hashCode() ?: 0
        firstReadyElapsed = -1L
        firstPlayingElapsed = -1L
        rebufferCount = 0
        rebufferTotalMs = 0L
        rebufferStartElapsed = -1L
        droppedFrames = 0
        decoderName = null
        decoderInitMs = -1L
        codec = null
        container = null
        bitrate = -1
        everReady = false
        PlaybackBenchmark.trace(index, "TRANSITION")
    }

    // ---- AnalyticsListener observations ----

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        if (!PlaybackBenchmark.enabled) return
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_BUFFERING -> {
                PlaybackBenchmark.trace(itemIndex, "BUFFERING")
                // Only counts as a rebuffer after the first READY.
                if (everReady && rebufferStartElapsed < 0) {
                    rebufferStartElapsed = now
                }
            }
            Player.STATE_READY -> {
                PlaybackBenchmark.trace(itemIndex, "READY")
                if (!everReady) {
                    everReady = true
                    firstReadyElapsed = now
                    emitIfComplete()
                } else if (rebufferStartElapsed >= 0) {
                    rebufferCount++
                    rebufferTotalMs += now - rebufferStartElapsed
                    rebufferStartElapsed = -1L
                }
            }
            Player.STATE_ENDED -> {
                PlaybackBenchmark.trace(itemIndex, "ENDED")
                emitIfComplete()
            }
            Player.STATE_IDLE -> {
                PlaybackBenchmark.trace(itemIndex, "IDLE")
            }
        }
    }

    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
        if (!PlaybackBenchmark.enabled || !isPlaying) return
        if (firstPlayingElapsed < 0) {
            firstPlayingElapsed = SystemClock.elapsedRealtime()
            PlaybackBenchmark.trace(itemIndex, "PLAYING")
            emitIfComplete()
        }
    }

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long,
    ) {
        if (!PlaybackBenchmark.enabled) return
        PlaybackBenchmark.trace(itemIndex, "FIRST_FRAME")
        if (firstReadyElapsed < 0) {
            firstReadyElapsed = SystemClock.elapsedRealtime()
        }
        emitIfComplete()
    }

    // Media3 1.7.x declares both arities; override both so neither is missed.
    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        if (!PlaybackBenchmark.enabled) return
        this.decoderName = decoderName
        this.decoderInitMs = initializationDurationMs
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
    ) {
        if (!PlaybackBenchmark.enabled) return
        this.decoderName = decoderName
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        if (!PlaybackBenchmark.enabled) return
        this.droppedFrames += droppedFrames
    }

    override fun onDownstreamFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        mediaLoadData: MediaLoadData,
    ) {
        if (!PlaybackBenchmark.enabled) return
        val format: Format? = mediaLoadData.trackFormat
        if (format != null) {
            if (format.sampleMimeType?.startsWith("video") == true || codec == null) {
                codec = format.sampleMimeType
                container = format.containerMimeType
                if (format.bitrate != Format.NO_VALUE) bitrate = format.bitrate
            }
        }
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        if (!PlaybackBenchmark.enabled) return
        PlaybackBenchmark.trace(itemIndex, "ERROR")
        PlaybackBenchmark.note("player_error code=${error.errorCodeName} msg=${error.message}")
    }

    @Synchronized
    private fun emitIfComplete() {
        if (!PlaybackBenchmark.enabled) return
        // Emit once both first-frame and playing are known (steady state).
        if (firstReadyElapsed < 0 || firstPlayingElapsed < 0) return
        if (emittedForReady == firstReadyElapsed && emittedForPlaying == firstPlayingElapsed) return
        emittedForReady = firstReadyElapsed
        emittedForPlaying = firstPlayingElapsed
        val setStart = PlaybackBenchmark.setItemsElapsedMs
        val ttff = if (setStart >= 0) firstReadyElapsed - setStart else -1L
        val ttp = if (setStart >= 0) firstPlayingElapsed - setStart else -1L
        // Gap from transition (auto-advance OR manual Next) to first frame.
        // -1 for the very first item, which has no previous transition.
        val gap = if (itemCount <= 1 || transitionElapsed < 0) {
            -1L
        } else {
            firstReadyElapsed - transitionElapsed
        }
        PlaybackBenchmark.csvRow(
            itemIndex = itemIndex,
            uriHash = uriHash,
            codec = codec,
            container = container,
            bitrate = bitrate,
            ttffMs = ttff,
            timeToPlayingMs = ttp,
            rebufferCount = rebufferCount,
            rebufferTotalMs = rebufferTotalMs,
            nextGapMs = gap,
            droppedFrames = droppedFrames,
            decoderName = decoderName,
            decoderInitMs = decoderInitMs,
        )
    }

    @Volatile
    private var emittedForReady: Long = -2L

    @Volatile
    private var emittedForPlaying: Long = -2L
}
