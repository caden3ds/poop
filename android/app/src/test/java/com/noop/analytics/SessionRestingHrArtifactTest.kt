package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daily resting HR must not be set by a single artifact beat.
 *
 * A resting HR of 39 was reported for a night whose heart rate never visibly dropped below 41. Two
 * things combined:
 *
 *  - [SleepStager.sessionRestingHR] — the path that actually produces the number on screen, via the
 *    sleep-session rows and AnalyticsEngine's per-day minimum — took the plain minimum of 5-minute bin
 *    means with no guard. A bin holding ONE dropout reading has that reading as its "mean", so it won
 *    the floor outright.
 *  - [RecoveryScorer.restingHR] already had exactly the guards for this, added under #686 — but it had
 *    no production callers at all. It was reachable only from its own unit test, so the hardening
 *    protected nothing.
 *
 * The charted minimum did not contradict the bad value either: the chart reads a downsampled bucket
 * mean, which averaged the same artifact away.
 *
 * These tests exercise the SESSION entry point specifically, since that is the one that ships.
 */
class SessionRestingHrArtifactTest {

    private val t0 = 1_700_000_000L

    /** A dense, well-populated stretch at [bpm], one sample a second. */
    private fun dense(fromOffsetS: Long, seconds: Int, bpm: Int): List<HrSample> =
        (0 until seconds).map { HrSample(deviceId = "t", ts = t0 + fromOffsetS + it, bpm = bpm) }

    @Test
    fun `a lone artifact beat cannot become the resting HR`() {
        // 40 min of genuine sleep at 41 bpm, plus ONE stray 39 sitting alone in its own 5-min bin.
        val hr = dense(0, 40 * 60, 41) + listOf(HrSample(deviceId = "t", ts = t0 + 45 * 60, bpm = 39))
        val rhr = SleepStager.sessionRestingHR(start = t0, end = t0 + 50 * 60, hr = hr)
        assertEquals("the sustained 41 is the floor, not the stray 39", 41, rhr)
    }

    @Test
    fun `a sustained genuine dip IS still the resting HR`() {
        // The guard must not blunt the measurement: a real, well-populated low stretch still wins.
        val hr = dense(0, 20 * 60, 48) + dense(20 * 60, 10 * 60, 39)
        val rhr = SleepStager.sessionRestingHR(start = t0, end = t0 + 30 * 60, hr = hr)
        assertEquals("ten sustained minutes at 39 is a real floor", 39, rhr)
    }

    @Test
    fun `a sub-physiological run of dropouts is rejected`() {
        // A whole bin of decode-zero/dropout beats is not bradycardia.
        val hr = dense(0, 20 * 60, 44) + dense(20 * 60, 6 * 60, 12)
        val rhr = SleepStager.sessionRestingHR(start = t0, end = t0 + 30 * 60, hr = hr)
        assertTrue("an implausible run must not set the floor, got $rhr", (rhr ?: 0) >= 25)
    }

    @Test
    fun `resting HR never falls below the minimum observed beat`() {
        // The invariant the report violated, stated directly.
        val hr = dense(0, 30 * 60, 41) + listOf(HrSample(deviceId = "t", ts = t0 + 35 * 60, bpm = 39))
        val minBeat = hr.minOf { it.bpm }
        val rhr = SleepStager.sessionRestingHR(start = t0, end = t0 + 40 * 60, hr = hr)!!
        assertTrue("resting HR $rhr must not sit below the lowest beat $minBeat", rhr >= minBeat)
    }

    @Test
    fun `no samples still means no number`() {
        assertEquals(null, SleepStager.sessionRestingHR(t0, t0 + 600, emptyList()))
    }
}
