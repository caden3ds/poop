package com.noop.analytics

import com.noop.analytics.LucidCuePolicy.CueStrength
import com.noop.analytics.LucidCuePolicy.Decision
import com.noop.analytics.LucidCuePolicy.NightState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LucidCuePolicy] — the restraint rules for the lucid-dream cue.
 *
 * This is the feature that deliberately buzzes a sleeping person, so its refusals matter more than its
 * firings. Each safety rule gets a test that proves it BLOCKS, not just that the happy path works: a
 * regression here costs the user a night's sleep, and cannot be caught without spending a night on it.
 */
class LucidCuePolicyTest {

    private val idle = NightState()

    /** A state that is eligible to cue: in REM, nothing spent, no arousal. */
    private fun eligible(minutesInRem: Int = LucidCuePolicy.MIN_MINUTES_INTO_REM) =
        LucidCuePolicy.decide(inRem = true, minutesInRem = minutesInRem, state = idle, enabled = true)

    // ── Firing ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `cues gently once the REM period is established`() {
        val d = eligible()
        assertTrue(d is Decision.Cue)
        assertEquals(CueStrength.GENTLE, (d as Decision.Cue).strength)
    }

    @Test
    fun `the ramp is salience, not intensity`() {
        // No amplitude exists on the strap, so the second attempt differs by REPEATS of the triple.
        // Explicitly NOT by loop count: more loops lengthens each pulse, which is what made the three
        // pulses merge into one buzz indistinguishable from a notification.
        assertTrue(CueStrength.SALIENT.bursts > CueStrength.GENTLE.bursts)
        assertEquals(1, CueStrength.GENTLE.bursts)
    }

    @Test
    fun `a second attempt in the same period is more salient`() {
        val state = NightState(cuesThisPeriod = 1, cuesTonight = 1, minutesSinceLastCue = LucidCuePolicy.MIN_SPACING_MIN)
        val d = LucidCuePolicy.decide(inRem = true, minutesInRem = 30, state = state, enabled = true)
        assertEquals(CueStrength.SALIENT, (d as Decision.Cue).strength)
    }

    // ── Refusing ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `never cues when the user switched it off`() {
        val d = LucidCuePolicy.decide(inRem = true, minutesInRem = 30, state = idle, enabled = false)
        assertTrue("the master switch must be checked in the policy itself", d is Decision.Hold)
    }

    @Test
    fun `never cues outside REM`() {
        assertTrue(LucidCuePolicy.decide(false, 30, idle, enabled = true) is Decision.Hold)
    }

    @Test
    fun `does not cue at the very start of a REM period`() {
        // A cue landing as REM begins tends to end it, which defeats the point.
        val d = LucidCuePolicy.decide(true, LucidCuePolicy.MIN_MINUTES_INTO_REM - 1, idle, enabled = true)
        assertTrue(d is Decision.Hold)
    }

    @Test
    fun `does not open a first attempt late in the period`() {
        val d = LucidCuePolicy.decide(true, LucidCuePolicy.MAX_MINUTES_INTO_REM + 1, idle, enabled = true)
        assertTrue(d is Decision.Hold)
    }

    @Test
    fun `respects the per-period budget`() {
        val spent = NightState(
            cuesThisPeriod = LucidCuePolicy.MAX_CUES_PER_PERIOD,
            cuesTonight = LucidCuePolicy.MAX_CUES_PER_PERIOD,
            minutesSinceLastCue = 60,
        )
        assertTrue(LucidCuePolicy.decide(true, 30, spent, enabled = true) is Decision.Hold)
    }

    @Test
    fun `respects the whole-night budget`() {
        val spent = NightState(cuesThisPeriod = 0, cuesTonight = LucidCuePolicy.MAX_CUES_PER_NIGHT, minutesSinceLastCue = 60)
        assertTrue(LucidCuePolicy.decide(true, 12, spent, enabled = true) is Decision.Hold)
    }

    @Test
    fun `respects minimum spacing so a ramp cannot become a burst`() {
        val tooSoon = NightState(cuesThisPeriod = 1, cuesTonight = 1, minutesSinceLastCue = LucidCuePolicy.MIN_SPACING_MIN - 1)
        assertTrue(LucidCuePolicy.decide(true, 30, tooSoon, enabled = true) is Decision.Hold)
    }

    @Test
    fun `stands down for the rest of the period after arousal`() {
        val aroused = NightState(cuesThisPeriod = 1, cuesTonight = 1, minutesSinceLastCue = 60, arousalAbortedPeriod = true)
        assertTrue(LucidCuePolicy.decide(true, 30, aroused, enabled = true) is Decision.Hold)
    }

    // ── Arousal detection ────────────────────────────────────────────────────────────────────────

    @Test
    fun `arousal is detected on a clear rise above baseline`() {
        val post = List(5) { 58.0 + LucidCuePolicy.AROUSAL_RISE_BPM }
        assertTrue(LucidCuePolicy.arousalDetected(baselineBpm = 58.0, postCueBpm = post))
    }

    @Test
    fun `a settled sleeper is not read as aroused`() {
        assertFalse(LucidCuePolicy.arousalDetected(58.0, listOf(58.0, 59.0, 57.5, 58.5)))
    }

    @Test
    fun `too little post-cue data is not arousal`() {
        // "No evidence" must not read as "aroused"; the spacing rule independently prevents another
        // cue landing before the watch window has filled.
        assertFalse(LucidCuePolicy.arousalDetected(58.0, listOf(90.0, 95.0)))
    }

    @Test
    fun `no baseline means no arousal claim`() {
        assertFalse(LucidCuePolicy.arousalDetected(null, List(5) { 120.0 }))
        assertFalse(LucidCuePolicy.arousalDetected(0.0, List(5) { 120.0 }))
    }
}
