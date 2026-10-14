package com.example.rustydlnaclient.perf

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log

/**
 * Zero-dependency benchmark harness. All output goes to logcat on [TAG]
 * so results can be captured with:
 *
 *   adb logcat -c; adb logcat -s PerfBenchmark:V > run.csv
 *
 * No androidx.benchmark, no file I/O, no third-party logger. Every method
 * is a no-op when [enabled] is false, keeping release overhead at zero.
 */
object PlaybackBenchmark {

    const val TAG = "PerfBenchmark"
    const val TRACE_TAG = "PerfTrace"

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var profileName: String = PlayerConfigProfiles.BASELINE_NAME

    @Volatile
    var runTag: String = "1"

    /** Monotonic start mark for setMediaItems -> first-frame timing. */
    @Volatile
    var setItemsElapsedMs: Long = -1L

    /**
     * Enables logging on debuggable builds without any generated class.
     * Safe to call from any component (MainActivity runs before any player
     * exists, so it cannot rely on the player's setup gate).
     */
    fun ensureEnabled(context: Context) {
        if (enabled) return
        try {
            val flags = context.applicationContext.applicationInfo.flags
            if (flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) enabled = true
        } catch (_: Exception) {
        }
    }

    fun markSetMediaItems() {
        setItemsElapsedMs = SystemClock.elapsedRealtime()
    }

    fun elapsedSinceSetItemsMs(): Long {
        val start = setItemsElapsedMs
        if (start < 0) return -1
        return SystemClock.elapsedRealtime() - start
    }

    /**
     * Emits one CSV row. Column order is stable so runs can be pasted
     * into a spreadsheet and compared with median().
     */
    fun csvRow(
        itemIndex: Int,
        uriHash: Int,
        codec: String?,
        container: String?,
        bitrate: Int,
        ttffMs: Long,
        timeToPlayingMs: Long,
        rebufferCount: Int,
        rebufferTotalMs: Long,
        nextGapMs: Long,
        droppedFrames: Int,
        decoderName: String?,
        decoderInitMs: Long,
    ) {
        if (!enabled) return
        Log.v(
            TAG,
            "profile=$profileName, run=$runTag, idx=$itemIndex, uriHash=$uriHash" +
                ", codec=${codec ?: "?"}, container=${container ?: "?"}, bitrate=$bitrate" +
                ", ttff_ms=$ttffMs, ttp_ms=$timeToPlayingMs" +
                ", rebuf_n=$rebufferCount, rebuf_ms=$rebufferTotalMs, next_gap_ms=$nextGapMs" +
                ", dropped=$droppedFrames, decoder=${decoderName ?: "?"}" +
                ", dec_init_ms=$decoderInitMs",
        )
    }

    /** Ordered Player.Listener callback sequence for functional-parity diffs. */
    fun trace(itemIndex: Int, event: String) {
        if (!enabled) return
        Log.v(TRACE_TAG, "profile=$profileName, run=$runTag, idx=$itemIndex, seq=$event")
    }

    /**
     * DLNA browse (XML) benchmark row. Column order is stable for
     * spreadsheet medians: net = socket read, soap = envelope parse,
     * didl = payload parse, extract = container/item loops, ui = UI-thread
     * clear+addAll+notify, prog = progressive (per-100) UI posts.
     */
    fun xmlRow(
        reqId: String,
        seq: Int,
        respChars: Int,
        netMs: Long,
        soapMs: Long,
        didlMs: Long,
        extractMs: Long,
        containers: Int,
        videos: Int,
        progPosts: Int,
        uiMs: Long,
        gapMs: Long = -1,
        logMs: Long = -1,
        decMs: Long = -1,
        fixMs: Long = -1,
        sanMs: Long = -1,
        parseMs: Long = -1,
        didChars: Long = -1,
    ) {
        if (!enabled) return
        Log.v(
            TAG,
            "xml run=$runTag, seq=$seq, req=$reqId, resp_chars=$respChars" +
                ", net_ms=$netMs, gap_ms=$gapMs, soap_ms=$soapMs, didl_ms=$didlMs" +
                ", extract_ms=$extractMs, containers=$containers, videos=$videos" +
                ", prog_posts=$progPosts, ui_ms=$uiMs" +
                ", parse_total_ms=${soapMs + didlMs + extractMs}" +
                ", sub_log_ms=$logMs, sub_dec_ms=$decMs, sub_fix_ms=$fixMs" +
                ", sub_san_ms=$sanMs, sub_parse_ms=$parseMs, did_chars=$didChars",
        )
    }

    fun note(message: String) {
        if (!enabled) return
        Log.v(TAG, "profile=$profileName, run=$runTag, note=$message")
    }

    /** Median of a sample; pure Kotlin so it is JVM-unit-testable. */
    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return -1
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
