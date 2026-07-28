package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LiveState.hrRecent] — the rolling heart-rate buffer behind the Live tab's HR graph.
 *
 * The graph is only as trustworthy as this buffer, so the properties that keep it honest are pinned here:
 * it appends in arrival order (a trace read left-to-right must be oldest-to-newest), it is bounded (an
 * all-day stream cannot grow without limit), and it is cleared on disconnect so a stale trace can never
 * outlive the link it came from.
 */
class LiveHrBufferTest {

    @Test
    fun `appends in arrival order`() {
        var s = LiveState()
        listOf(61, 64, 63, 70).forEach { s = s.withHeartRate(it) }
        assertEquals(listOf(61, 64, 63, 70), s.hrRecent)
        assertEquals(70, s.heartRate)
    }

    @Test
    fun `caps at the limit and drops the oldest first`() {
        var s = LiveState()
        for (i in 1..150) s = s.withHeartRate(i)
        assertEquals(120, s.hrRecent.size)
        // The 30 oldest fell off, so the window is 31..150 — newest still last.
        assertEquals(31, s.hrRecent.first())
        assertEquals(150, s.hrRecent.last())
        assertEquals(150, s.heartRate)
    }

    @Test
    fun `honours a custom limit`() {
        var s = LiveState()
        for (i in 1..10) s = s.withHeartRate(i, recentLimit = 4)
        assertEquals(listOf(7, 8, 9, 10), s.hrRecent)
    }

    @Test
    fun `rejects non-positive sentinels without disturbing the buffer`() {
        var s = LiveState().withHeartRate(65)
        s = s.withHeartRate(0)
        s = s.withHeartRate(-1)
        assertEquals(listOf(65), s.hrRecent)
        assertEquals(65, s.heartRate)
    }

    /**
     * A dropped link must leave nothing behind to draw. The graph reads straight off this buffer, so a
     * surviving trace would render as a live heart rate for a strap that is no longer connected.
     */
    @Test
    fun `clearedBiometrics empties the graph buffer`() {
        var s = LiveState()
        listOf(60, 62, 64).forEach { s = s.withHeartRate(it) }
        val cleared = s.clearedBiometrics()
        assertTrue(cleared.hrRecent.isEmpty())
        assertEquals(null, cleared.heartRate)
        assertTrue(cleared.rrRecent.isEmpty())
    }

    /** The HR buffer and the R-R buffer are independent: recording one must not perturb the other. */
    @Test
    fun `hr and rr buffers do not interfere`() {
        val s = LiveState()
            .withHeartRate(66)
            .withRRIntervals(listOf(900, 910))
            .withHeartRate(68)
        assertEquals(listOf(66, 68), s.hrRecent)
        assertEquals(listOf(900, 910), s.rrRecent)
    }
}
