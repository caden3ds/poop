package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * Pins the Effort/Strain curve: what it must do that the old one did not.
 *
 * TWO defects were fixed together.
 *
 * 1. Edwards was the default. Its five integer zone weights start at 50% of heart-rate reserve, so
 *    every beat below that contributed exactly zero. For a 46 bpm resting rate and a 190 bpm maximum
 *    that is a 118 bpm cut-off — a whole day of walking about scored 0, which is what the tile showed.
 *
 * 2. The map was 100 × ln(TRIMP+1) / ln(7201), one free parameter against a 24-hour ceiling. It
 *    crushed the realistic range into five points: bed-bound 10.7, all-out 15.5, on a scale of 21.
 *
 * The day archetypes below are INTEGRATED here rather than hard-coded, so if the weighting changes
 * these expectations move with it and the anchors stay honest.
 */
class StrainCurveTest {

    private val restingHr = 46.0
    private val hrMax = 190.0
    private val reserve = hrMax - restingHr

    /** One minute of Banister load at [bpm], matching [StrainScorer.banisterTRIMP]. */
    private fun loadPerMinute(bpm: Double): Double {
        val x = ((bpm - restingHr) / reserve).coerceIn(0.0, 1.0)
        return if (x <= 0) 0.0 else StrainScorer.banisterScale * x * exp(StrainScorer.banisterBMen * x)
    }

    /** A day: [wakingHours] at [wakingBpm], the rest at [sleepBpm], plus an optional harder block. */
    private fun dayTrimp(
        sleepBpm: Double,
        wakingBpm: Double,
        wakingHours: Double,
        block: Pair<Double, Double>? = null,
    ): Double {
        var t = loadPerMinute(sleepBpm) * (24.0 - wakingHours) * 60.0 +
            loadPerMinute(wakingBpm) * wakingHours * 60.0
        block?.let { (bpm, minutes) -> t += (loadPerMinute(bpm) - loadPerMinute(wakingBpm)) * minutes }
        return t
    }

    private fun shown(trimp: Double): Double =
        StrainScorer.trimpToStrain(trimp) * UnitFormatterEffortFactor

    /** The stored 0–100 axis is shown on 0–21; kept local so this test needs no UI import. */
    private val UnitFormatterEffortFactor = 21.0 / 100.0

    @Test
    fun `an ordinary day of walking about is no longer worth nothing`() {
        // THE DEFECT. 82 bpm is 25% of this user's reserve — real cardiovascular work, and entirely
        // below Edwards' 50% floor.
        val ordinary = dayTrimp(sleepBpm = 50.0, wakingBpm = 82.0, wakingHours = 16.0)
        assertTrue("an ordinary day must accumulate real load, got $ordinary", ordinary > 100.0)
        assertTrue("and must land mid-scale, got ${shown(ordinary)}", shown(ordinary) in 10.0..15.0)
    }

    @Test
    fun `the day archetypes land in their intended bands`() {
        val rest = dayTrimp(52.0, 60.0, 16.0)
        val sedentary = dayTrimp(50.0, 72.0, 16.0)
        val ordinary = dayTrimp(50.0, 82.0, 16.0)
        val hard = dayTrimp(50.0, 82.0, 16.0, 165.0 to 60.0)
        val allOut = dayTrimp(50.0, 85.0, 16.0, 175.0 to 120.0)

        assertEquals("a restful day anchors near 6", 6.0, shown(rest), 1.0)
        assertEquals("an all-out day anchors near 19", 19.0, shown(allOut), 1.0)
        // Strictly ordered, with no two archetypes collapsing onto the same reading.
        val ladder = listOf(rest, sedentary, ordinary, hard, allOut).map { shown(it) }
        for (i in 1 until ladder.size) {
            assertTrue("band $i must exceed band ${i - 1}: $ladder", ladder[i] > ladder[i - 1])
        }
        assertTrue("the archetypes must span a useful range, got $ladder", ladder.last() - ladder.first() > 10.0)
    }

    @Test
    fun `each further point costs more than the last`() {
        // The property the scale exists for. Measured as the TRIMP needed to climb one more point.
        fun trimpFor(target: Double): Double =
            StrainScorer.strainCurveT0 * (exp(target / StrainScorer.strainCurveC) - 1.0)
        val costs = (6..19).map { trimpFor(it.toDouble() / 0.21) - trimpFor((it - 1).toDouble() / 0.21) }
        for (i in 1 until costs.size) {
            assertTrue("climbing must get harder at step $i: $costs", costs[i] > costs[i - 1])
        }
    }

    @Test
    fun `the curve stays bounded and honest at the extremes`() {
        assertEquals(0.0, StrainScorer.trimpToStrain(0.0), 0.0)
        assertEquals("negative load is not negative strain", 0.0, StrainScorer.trimpToStrain(-5.0), 0.0)
        assertTrue("an absurd load must clamp, not exceed the top",
            StrainScorer.trimpToStrain(1_000_000.0) <= StrainScorer.maxStrain)
    }

    @Test
    fun `a real sub-threshold HR series scores above zero end to end`() {
        // Through the public API, at 90 bpm — under Edwards' 118 bpm floor for this user.
        val hr = (0 until 3600).map { HrSample(deviceId = "t", ts = 1_700_000_000L + it, bpm = 90) }
        val effort = StrainScorer.strain(hr, maxHR = hrMax, restingHR = restingHr)
        assertNotNull(effort)
        assertTrue("an hour at 90 bpm must score above zero, got $effort", effort!! > 0.0)
    }
}
