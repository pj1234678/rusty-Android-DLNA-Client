package com.example.rustydlnaclient.perf

import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Buffer profiles under test. All values in milliseconds.
 *
 * Rationale (DLNA LAN: high bandwidth, high bitrate, zap-speed matters):
 * - bufferForPlayback*: lower = faster start, higher = fewer rebuffers.
 * - min/maxBufferMs: ceiling for pre-buffered media.
 * - backBufferMs: retained media behind playhead; makes replay-seek
 *   after resume/seek-back a memory hit instead of a network fetch.
 *
 * BASELINE_* literals mirror Media3 1.7.1 DefaultLoadControl defaults and
 * are intentionally hardcoded (not read from the class) so the control arm
 * stays frozen even if library defaults change, and so the profile table
 * stays JVM-unit-testable without initializing player classes.
 */

// Media3 1.7.1 DefaultLoadControl defaults (frozen control arm).
private const val BASELINE_MIN_BUFFER_MS = 50_000
private const val BASELINE_MAX_BUFFER_MS = 50_000
private const val BASELINE_BUFFER_FOR_PLAYBACK_MS = 2_500
private const val BASELINE_BUFFER_AFTER_REBUFFER_MS = 5_000
private const val BASELINE_PRIORITIZE_TIME_OVER_SIZE = false
data class BufferProfile(
    val name: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val backBufferMs: Int,
    val prioritizeTimeOverSize: Boolean = true,
) {
    fun buildLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs,
            )
            .setBackBuffer(backBufferMs, false)
            .setPrioritizeTimeOverSizeThresholds(prioritizeTimeOverSize)
            .build()
    }
}

object PlayerConfigProfiles {

    const val BASELINE_NAME = "baseline"
    const val ZAP_NAME = "A_zap"
    const val BALANCED_NAME = "B_balanced"
    const val HEAVY_NAME = "C_heavy"

    /** Control arm: ExoPlayer defaults — no override, mirrors old behavior. */
    val BASELINE = BufferProfile(
        name = BASELINE_NAME,
        minBufferMs = BASELINE_MIN_BUFFER_MS,
        maxBufferMs = BASELINE_MAX_BUFFER_MS,
        bufferForPlaybackMs = BASELINE_BUFFER_FOR_PLAYBACK_MS,
        bufferForPlaybackAfterRebufferMs = BASELINE_BUFFER_AFTER_REBUFFER_MS,
        backBufferMs = 0,
        prioritizeTimeOverSize = BASELINE_PRIORITIZE_TIME_OVER_SIZE,
    )

    /** Fastest zap for small files / good WiFi. */
    val ZAP = BufferProfile(
        name = ZAP_NAME,
        minBufferMs = 10_000,
        maxBufferMs = 30_000,
        bufferForPlaybackMs = 1_000,
        bufferForPlaybackAfterRebufferMs = 2_000,
        backBufferMs = 0,
    )

    /** Proposed default: broad compat (phone + TV, mp4..mkv..ts, HW + SW decoders). */
    val BALANCED = BufferProfile(
        name = BALANCED_NAME,
        minBufferMs = 15_000,
        maxBufferMs = 50_000,
        bufferForPlaybackMs = 2_000,
        bufferForPlaybackAfterRebufferMs = 5_000,
        backBufferMs = 10_000,
    )

    /** Heavy files (HEVC/4K/m2ts) or weak WiFi / software decoders. */
    val HEAVY = BufferProfile(
        name = HEAVY_NAME,
        minBufferMs = 20_000,
        maxBufferMs = 60_000,
        bufferForPlaybackMs = 2_500,
        bufferForPlaybackAfterRebufferMs = 5_000,
        backBufferMs = 15_000,
    )

    val ALL: List<BufferProfile> = listOf(BASELINE, ZAP, BALANCED, HEAVY)

    /** Resolves EXTRA_BUFFER_PROFILE intent extra; unknown -> BALANCED. */
    fun resolve(name: String?): BufferProfile {
        return ALL.firstOrNull { it.name == name } ?: BALANCED
    }
}
