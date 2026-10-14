package com.example.rustydlnaclient.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only tests for buffer profile selection. No device needed. */
class PlayerConfigProfilesTest {

    @Test
    fun resolve_knownNames_returnMatchingProfile() {
        assertEquals(PlayerConfigProfiles.BASELINE, PlayerConfigProfiles.resolve("baseline"))
        assertEquals(PlayerConfigProfiles.ZAP, PlayerConfigProfiles.resolve("A_zap"))
        assertEquals(PlayerConfigProfiles.BALANCED, PlayerConfigProfiles.resolve("B_balanced"))
        assertEquals(PlayerConfigProfiles.HEAVY, PlayerConfigProfiles.resolve("C_heavy"))
    }

    @Test
    fun resolve_unknownOrNull_returnsBalancedDefault() {
        assertEquals(PlayerConfigProfiles.BALANCED, PlayerConfigProfiles.resolve("nope"))
        assertEquals(PlayerConfigProfiles.BALANCED, PlayerConfigProfiles.resolve(null))
    }

    @Test
    fun zap_startsFasterThanBaseline() {
        assertTrue(PlayerConfigProfiles.ZAP.bufferForPlaybackMs < PlayerConfigProfiles.BASELINE.bufferForPlaybackMs)
        assertTrue(
            PlayerConfigProfiles.ZAP.bufferForPlaybackAfterRebufferMs <
                PlayerConfigProfiles.BASELINE.bufferForPlaybackAfterRebufferMs,
        )
    }

    @Test
    fun balanced_hasBackBufferForInstantReplaySeek() {
        assertTrue(PlayerConfigProfiles.BALANCED.backBufferMs > 0)
        assertTrue(PlayerConfigProfiles.HEAVY.backBufferMs >= PlayerConfigProfiles.BALANCED.backBufferMs)
    }

    @Test
    fun allProfiles_haveDistinctNames() {
        val names = PlayerConfigProfiles.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertEquals(4, names.size)
    }
}
