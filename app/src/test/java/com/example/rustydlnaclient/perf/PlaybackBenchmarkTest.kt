package com.example.rustydlnaclient.perf

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM-only tests for the logcat benchmark harness math. No device needed. */
class PlaybackBenchmarkTest {

    @Test
    fun median_oddCount_returnsMiddle() {
        assertEquals(20L, PlaybackBenchmark.median(listOf(30L, 10L, 20L)))
    }

    @Test
    fun median_evenCount_returnsAverageOfMiddleTwo() {
        assertEquals(25L, PlaybackBenchmark.median(listOf(10L, 20L, 30L, 40L)))
    }

    @Test
    fun median_singleValue_returnsIt() {
        assertEquals(7L, PlaybackBenchmark.median(listOf(7L)))
    }

    @Test
    fun median_empty_returnsMinusOne() {
        assertEquals(-1L, PlaybackBenchmark.median(emptyList()))
    }

    @Test
    fun median_ignoresInputOrder() {
        val shuffled = listOf(500L, 100L, 300L, 200L, 400L)
        assertEquals(300L, PlaybackBenchmark.median(shuffled))
    }
}
