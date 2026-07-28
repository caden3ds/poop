package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RrInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins manual-sleep-only mode: with it ON, [AnalyticsEngine.analyzeDay] runs NO automatic detection —
 * the day's sleep is exactly the user's own logged windows, and those must be SCORED like detected
 * nights.
 *
 * The physiology assertions below are the regression guard for a real defect: the first cut of this
 * mode suppressed detection and left the user's night to the downstream edit-fold, which restores
 * duration + stages ONLY. Resting HR and HRV are derived from the day's matched sessions, so both
 * went permanently null in manual mode — silently killing HRV, RHR and therefore Charge, and leaving
 * the dashboard showing stale imported values. A manual night must yield the same physiology an
 * auto-detected night of the same span would.
 */
class ManualSleepOnlyTest {

    private val windowEnd = 1_767_333_600L   // 2026-01-02T06:00:00Z
    private val windowStart = windowEnd - 2 * 3600L
    private val day = "2026-01-02"

    /** A dense, perfectly still overnight gravity stream (1/min for 8 h from 22:00 UTC): constant
     *  posture → every stillness flag trips → V1 finds one long sleep run. The minimal honest fixture
     *  for "a night the detector WOULD find". */
    private fun overnightGravity(): List<GravitySample> {
        val start = windowEnd - 8 * 3600L
        return (0 until 8 * 60).map { i ->
            GravitySample(deviceId = "t", ts = start + i * 60L, x = 0.0, y = 0.0, z = 1.0)
        }
    }

    /** Sleeping HR across the manual window: ~52 bpm with a gentle dip, one sample/second. */
    private fun sleepingHr(): List<HrSample> =
        (0 until (windowEnd - windowStart).toInt()).map { i ->
            HrSample(deviceId = "t", ts = windowStart + i, bpm = 52 + (i / 900) % 3)
        }

    /** R-R intervals across the manual window: ~1150 ms with small beat-to-beat variation, so the
     *  cleaning pipeline keeps them (in range, non-ectopic) and each 5-min window clears MIN_BEATS. */
    private fun sleepingRr(): List<RrInterval> =
        (0 until (windowEnd - windowStart).toInt()).map { i ->
            RrInterval(deviceId = "t", ts = windowStart + i, rrMs = 1150 + (i % 7) * 6)
        }

    @Test
    fun defaultModeDetectsTheNight() {
        val res = AnalyticsEngine.analyzeDay(
            day = day, gravity = overnightGravity(), profile = UserProfile(),
        )
        assertTrue(
            "the fixture night must be auto-detected with the mode off (else the manual-mode tests prove nothing)",
            res.sleepSessions.isNotEmpty(),
        )
    }

    @Test
    fun manualSleepOnlyIgnoresAutomaticDetection() {
        // Same night the detector finds by default, but no user-logged window supplied → the mode must
        // report NOTHING rather than fall back to detection. (Honest cold state: nothing logged yet.)
        val res = AnalyticsEngine.analyzeDay(
            day = day, gravity = overnightGravity(), profile = UserProfile(),
            manualSleepOnly = true,
        )
        assertTrue(
            "manual-sleep-only must yield ZERO auto-detected sessions",
            res.sleepSessions.isEmpty(),
        )
    }

    @Test
    fun manualSleepOnlyAlsoSilencesV2Staging() {
        // The gate must sit ABOVE the V1/V2 fork — the experimental stager cannot resurrect detection.
        val res = AnalyticsEngine.analyzeDay(
            day = day, gravity = overnightGravity(), profile = UserProfile(),
            useSleepStagerV2 = true, manualSleepOnly = true,
        )
        assertTrue(res.sleepSessions.isEmpty())
    }

    @Test
    fun manualWindowIsScoredWithRestingHrAndHrv() {
        // THE REGRESSION GUARD: a user-logged night must produce real physiology, not just a duration.
        val res = AnalyticsEngine.analyzeDay(
            day = day,
            hr = sleepingHr(),
            rr = sleepingRr(),
            gravity = overnightGravity(),
            profile = UserProfile(),
            manualSleepOnly = true,
            manualSleepWindows = listOf(windowStart to windowEnd),
        )
        assertEquals("the user's window must become the day's session", 1, res.sleepSessions.size)
        val session = res.sleepSessions.first()
        assertEquals(windowStart, session.start)
        assertEquals(windowEnd, session.end)
        assertNotNull("manual night must carry a resting HR", session.restingHR)
        assertNotNull("manual night must carry an HRV (RMSSD)", session.avgHRV)
        // …and those must reach the DAILY row the dashboard + Charge read.
        assertNotNull("daily restingHr must be populated in manual mode", res.daily.restingHr)
        assertNotNull("daily avgHrv must be populated in manual mode", res.daily.avgHrv)
        assertNotNull("the logged night must also report its duration", res.daily.totalSleepMin)
        assertTrue(
            "resting HR should reflect the sleeping fixture (~52 bpm)",
            res.daily.restingHr!! in 45..60,
        )
    }

    @Test
    fun manualWindowMatchesDetectedScoringForTheSameSpan() {
        // A manual window and an auto-detected night over the SAME span must agree on physiology —
        // the whole point of routing manual windows through the identical per-session path.
        val hr = sleepingHr()
        val rr = sleepingRr()
        val manual = SleepStager.scoreManualWindows(
            windows = listOf(windowStart to windowEnd),
            hr = hr, rr = rr, gravity = overnightGravity(),
        ).first()
        assertEquals(
            SleepStager.sessionRestingHR(windowStart, windowEnd, hr.sortedBy { it.ts }),
            manual.restingHR,
        )
        assertEquals(
            SleepStager.sessionAvgHRV(windowStart, windowEnd, rr.sortedBy { it.ts }),
            manual.avgHRV,
        )
    }

    @Test
    fun invertedAndDuplicateWindowsAreDropped() {
        val scored = SleepStager.scoreManualWindows(
            windows = listOf(
                windowEnd to windowStart,              // inverted → dropped
                windowStart to windowEnd,              // kept
                windowStart to (windowEnd - 600L),     // same onset logged twice → first wins
            ),
            hr = sleepingHr(), rr = sleepingRr(), gravity = overnightGravity(),
        )
        assertEquals(1, scored.size)
        assertEquals(windowEnd, scored.first().end)
        assertNull(
            "an empty window list must score nothing",
            SleepStager.scoreManualWindows(windows = emptyList(), gravity = emptyList()).firstOrNull(),
        )
    }
}
