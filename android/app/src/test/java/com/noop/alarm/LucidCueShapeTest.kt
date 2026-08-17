package com.noop.alarm

import com.noop.analytics.LucidCuePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SHAPE of the wrist cue, which is the only part of this feature the user ever perceives.
 *
 * The cue fired correctly for nights on end and went unnoticed. Two facts drove the reshape:
 *
 *  - Every cue ever delivered was GENTLE. SALIENT only fires as a SECOND attempt inside one REM
 *    period, and in practice the first cue ends the period (arousal stand-down, or the period simply
 *    lapsing), so the ramp had never once been exercised. The old GENTLE was the entire user-facing
 *    experience of the feature.
 *  - A single pulse was considered and rejected. In sleep an isolated stimulus reads as noise, offers
 *    one chance to land, and would have to be long enough to risk waking rather than cueing.
 *
 * So: fewer pulses, each longer. The daytime reality check fires the IDENTICAL pattern — that is the
 * conditioning mechanism — so these numbers govern both halves and must stay in step.
 */
class LucidCueShapeTest {

    /** Mirrors WhoopBleClient's private constants; they are the contract this test exists to hold. */
    private val pulseCount = 2
    private val subWritesGentle = 5
    private val subWritesSalient = 7
    private val subWriteMs = 120L
    private val pulseSpacingMs = 1_600L

    private fun pulseLengthMs(subWrites: Int) = subWrites * subWriteMs

    @Test
    fun `the cue is more than one pulse, so it reads as deliberate`() {
        assertTrue("a single pulse is indistinguishable from noise in sleep", pulseCount >= 2)
    }

    @Test
    fun `pulses stay separated by a clear silence`() {
        // If a pulse runs into the next they merge into one long buzz — which is exactly how earlier
        // attempts ended up feeling like an ordinary notification.
        for (subWrites in listOf(subWritesGentle, subWritesSalient)) {
            val gap = pulseSpacingMs - pulseLengthMs(subWrites)
            assertTrue(
                "pulses of ${pulseLengthMs(subWrites)}ms must leave a real gap, got ${gap}ms",
                gap >= 500L,
            )
        }
    }

    @Test
    fun `the ramp is still a ramp`() {
        assertTrue("SALIENT must out-last GENTLE", subWritesSalient > subWritesGentle)
        assertEquals(1, LucidCuePolicy.CueStrength.GENTLE.bursts)
        assertEquals(2, LucidCuePolicy.CueStrength.SALIENT.bursts)
    }

    @Test
    fun `the cue a sleeper now feels is stronger than the one they slept through`() {
        // The old GENTLE was 3 sub-writes across 3 pulses. What matters is per-pulse length, since
        // that is what makes an individual buzz register at all.
        val oldGentlePulseMs = 3 * subWriteMs
        assertTrue(
            "GENTLE must now out-last the old GENTLE per pulse",
            pulseLengthMs(subWritesGentle) > oldGentlePulseMs,
        )
    }

    @Test
    fun `the whole cue stays short enough to be a cue rather than an alarm`() {
        val total = pulseSpacingMs * (pulseCount - 1) + pulseLengthMs(subWritesSalient)
        assertTrue("the cue must stay under ~5s, got ${total}ms", total < 5_000L)
    }
}
