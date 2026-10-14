package com.example.rustydlnaclient

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import com.example.rustydlnaclient.perf.BufferProfile
import com.example.rustydlnaclient.perf.PlaybackBenchmark

/**
 * Process-wide warmed + reused ExoPlayer.
 *
 * Why: every video open used to pay `ExoPlayer.Builder` (native load,
 * thread spin-up, DefaultRenderersFactory) plus first Activity layout.
 * This holder lets MainActivity pre-warm one instance at app load
 * (cost hidden during SSDP browsing) and lets VideoPlayerActivity park
 * it on destroy instead of releasing, so the 2nd+ video skips the build.
 *
 * Rules:
 * - Main-thread only (all call sites are). Off-thread calls log and proceed.
 * - Built with the APPLICATION context so a parked player never leaks an
 *   Activity. Views/MediaSession stay per-Activity as before.
 * - Profile mismatch (A/B buffer runs) rebuilds — never serves the wrong
 *   LoadControl.
 * - park() resets playback state (stop/clearMediaItems/no-autoplay/detach
 *   surface). Callers must remove their own listeners BEFORE parking
 *   (release() used to do that implicitly).
 * - Overlapping sessions: the already-parked instance wins; the incoming
 *   one is released (same as the old always-release path — no leak).
 */
object PlayerHolder {
    private const val TAG = "PlayerHolder"

    private var player: ExoPlayer? = null
    private var profileName: String? = null

    /** Build one warmed instance now; no-op if one is already held. */
    @Synchronized
    fun warmup(appContext: Context, profile: BufferProfile) {
        if (player != null) return
        checkMain("warmup")
        try {
            val t0 = SystemClock.elapsedRealtime()
            player = ExoPlayer.Builder(appContext.applicationContext)
                .setLoadControl(profile.buildLoadControl())
                .build()
            profileName = profile.name
            PlaybackBenchmark.note(
                "player_warmup_ms=${SystemClock.elapsedRealtime() - t0} profile=${profile.name}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "warmup failed, player will build lazily", e)
            player = null
        }
    }

    /**
     * Take the held instance (reset by park()) or build one. The caller
     * owns it until park()/release() and must set playlist + listeners.
     */
    @Synchronized
    fun acquire(appContext: Context, profile: BufferProfile): ExoPlayer {
        checkMain("acquire")
        val parked = player
        player = null
        if (parked != null && profileName == profile.name) {
            PlaybackBenchmark.note("player_reuse=hit profile=${profile.name}")
            return parked
        }
        if (parked != null) {
            PlaybackBenchmark.note("player_reuse=miss reason=profile-changed")
            try {
                parked.release()
            } catch (_: Exception) {
            }
        } else {
            PlaybackBenchmark.note("player_reuse=miss reason=cold")
        }
        return try {
            ExoPlayer.Builder(appContext.applicationContext)
                .setLoadControl(profile.buildLoadControl())
                .build()
                .also { profileName = profile.name }
        } catch (e: Exception) {
            // Last resort: mirror the old Activity-context build.
            Log.w(TAG, "app-context build failed, retrying", e)
            ExoPlayer.Builder(appContext)
                .setLoadControl(profile.buildLoadControl())
                .build()
                .also { profileName = profile.name }
        }
    }

    /** Return an instance to the pool (state reset). Null-safe. */
    @Synchronized
    fun park(p: ExoPlayer?) {
        if (p == null) return
        checkMain("park")
        try {
            p.stop()
            p.clearMediaItems()
            p.playWhenReady = false
            p.clearVideoSurface()
        } catch (e: Exception) {
            Log.w(TAG, "park reset failed, releasing", e)
            try {
                p.release()
            } catch (_: Exception) {
            }
            return
        }
        val existing = player
        if (existing != null && existing !== p) {
            try {
                p.release()
            } catch (_: Exception) {
            }
            return
        }
        player = p
    }

    /** Free the held instance (app finishing). Active players are untouched. */
    @Synchronized
    fun release() {
        val p = player
        player = null
        profileName = null
        if (p != null) {
            try {
                p.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun checkMain(where: String) {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            Log.w(TAG, "$where called off main thread")
        }
    }
}
