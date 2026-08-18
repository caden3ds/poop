package com.noop.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the DEFAULT re-buzz thresholds, which is where this feature was broken.
 *
 * [RebuzzWatcherTest] constructs its watcher with every parameter overridden, so it verified the
 * mechanism perfectly while saying nothing about the numbers actually shipped — and the shipped
 * numbers made the feature impossible. The band was 4 bpm above the night's TROUGH, which is the
 * single lowest five-minute bucket of the whole night. Replayed over 21 scored nights it fired on
 * ZERO, while the hypnogram said the sleeper was genuinely asleep again on 12 of them.
 *
 * The replay that produced these values, per night: take the trough as NightTroughTracker computes it,
 * walk the 30 minutes after the 08:00 alarm, and require HR at or under the band continuously for the
 * sustain window.
 *
 *     trough+4,  3 min:   0 of 12 caught,  0 false
 *     trough+12, 3 min:   6 of 12 caught,  2 false   (75% precision)
 *     trough+14, 4 min:   8 of 12 caught,  2 false   (80% precision)   <- shipped
 *     trough+18, 4 min:  10 of 12 caught,  3 false   (77% precision)
 *
 * Precision is weighted over recall deliberately: a false positive arms the strap's own firmware
 * alarm, which buzzes until double-tapped off, at someone who is already up.
 */
class RebuzzCalibrationTest {

    private val t0 = 1_000_000L

    /** The shipped watcher — no overrides. That is the whole point of this file. */
    private fun shipped() = RebuzzWatcher()

    /**
     * The real morning that prompted this, from the night log of 2026-08-17: alarm at 08:00, measured
     * trough 40 bpm, and these heart rates over the following half hour. The old band (<=44) was never
     * touched — the lowest reading was 48.
     */
    private val realMorningHr = listOf(57, 53, 51, 67, 48, 67, 47, 49, 48, 47, 48, 48)

    @Test
    fun `the old band was unreachable on a real morning`() {
        val trough = 40
        val oldBand = trough + 4
        assertTrue(
            "no reading that morning came within 4 bpm of the trough — lowest was ${realMorningHr.min()}",
            realMorningHr.none { it <= oldBand },
        )
    }

    @Test
    fun `the shipped band reaches a real fall-back-asleep`() {
        val w = shipped()
        w.arm(t0, troughBpm = 40)
        // Sustained sleeping heart rate at 48 — comfortably inside trough+14, and exactly what the
        // sleeper actually produced after the alarm.
        var fired = false
        var t = t0 + 60_000L
        repeat(12) {
            if (w.shouldRebuzz(48, t)) fired = true
            t += 60_000L
        }
        assertTrue("a sustained 48 bpm over a 40 bpm trough must re-buzz", fired)
    }

    @Test
    fun `being up and about still does not re-buzz`() {
        val w = shipped()
        w.arm(t0, troughBpm = 40)
        // 62 bpm is 22 over the trough — someone awake and moving. Never within the band.
        var t = t0 + 60_000L
        repeat(20) {
            assertFalse("an awake heart rate must never re-buzz", w.shouldRebuzz(62, t))
            t += 60_000L
        }
    }

    @Test
    fun `a brief dip while lying awake is filtered by the sustain window`() {
        val w = shipped()
        w.arm(t0, troughBpm = 40)
        // Dips into the band, then breaks before the 4-minute hold completes. Repeatedly.
        var t = t0 + 60_000L
        repeat(6) {
            assertFalse(w.shouldRebuzz(50, t)); t += 60_000L
            assertFalse(w.shouldRebuzz(50, t)); t += 60_000L
            assertFalse("a broken run must not count", w.shouldRebuzz(70, t)); t += 60_000L
        }
    }

    @Test
    fun `no measured trough still means an honest no-op`() {
        val w = shipped()
        // Arming with a nonsense trough must not make the feature invent a re-buzz.
        w.arm(t0, troughBpm = 0)
        var t = t0 + 60_000L
        repeat(12) {
            assertFalse(w.shouldRebuzz(48, t)); t += 60_000L
        }
    }
}
