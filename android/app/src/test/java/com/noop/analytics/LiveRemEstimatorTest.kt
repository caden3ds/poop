package com.noop.analytics

import com.noop.analytics.LiveRemEstimator.Estimate
import com.noop.analytics.LiveRemEstimator.NightSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LiveRemEstimator] — the live "probably REM right now" read behind the lucid cue.
 *
 * The most important property is that it REFUSES to answer when it cannot: a wrong "yes" here fires a
 * buzz at a sleeping person, so cold start, a non-discriminating template and a thin stream must all
 * produce [Estimate.Unavailable] rather than a low-confidence guess.
 */
class LiveRemEstimatorTest {

    /** A night whose REM is both higher and less steady than its non-REM — the real pattern. */
    private fun trainingNight(): NightSample = NightSample(
        // REM: elevated ~8 bpm over the floor and visibly variable.
        remHr = listOf(58.0, 62.0, 57.0, 63.0, 59.0, 64.0, 58.0, 62.0, 60.0, 63.0),
        // Non-REM: a couple over the floor and flat.
        nonRemHr = listOf(52.0, 52.0, 53.0, 52.0, 53.0, 52.0, 52.0, 53.0, 52.0, 52.0),
        floorBpm = 51.0,
    )

    private fun template(nights: Int = LiveRemEstimator.MIN_TRAINING_NIGHTS) =
        LiveRemEstimator.learnTemplate(List(nights) { trainingNight() })

    // ── Learning ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `learns a separating template from enough nights`() {
        val t = template()!!
        assertTrue("REM must read as more elevated", t.remElevationBpm > t.nonRemElevationBpm)
        assertTrue("REM must read as less steady", t.remInstabilityBpm > t.nonRemInstabilityBpm)
        assertTrue(t.isDiscriminating)
    }

    @Test
    fun `refuses to learn from too few nights`() {
        assertEquals(null, LiveRemEstimator.learnTemplate(List(LiveRemEstimator.MIN_TRAINING_NIGHTS - 1) { trainingNight() }))
    }

    @Test
    fun `a template whose classes overlap is not discriminating`() {
        // REM and non-REM identical: matching against this would be noise dressed up as confidence.
        val flat = LiveRemEstimator.learnTemplate(
            List(4) { NightSample(remHr = List(10) { 55.0 }, nonRemHr = List(10) { 55.0 }, floorBpm = 50.0) },
        )!!
        assertFalse(flat.isDiscriminating)
        val e = LiveRemEstimator.estimate(List(12) { 55.0 }, 50.0, flat, minutesAsleep = 200)
        assertTrue(e is Estimate.Unavailable)
    }

    // ── Refusing to answer ───────────────────────────────────────────────────────────────────────

    @Test
    fun `cold start is silent`() {
        val e = LiveRemEstimator.estimate(List(20) { 60.0 }, 50.0, template = null, minutesAsleep = 200)
        assertTrue(e is Estimate.Unavailable)
    }

    @Test
    fun `no measured floor falls back to the learned typical floor`() {
        // Tonight's floor comes from a tracker needing hours of unbroken coverage in one service
        // lifetime, so it is null early and after any restart. Refusing outright cost a whole night:
        // floor=0 on every tick meant confidence was never computed rather than merely low. The
        // template's own median floor is the honest stand-in — every elevation figure in the template
        // is measured against exactly that kind of floor.
        val e = LiveRemEstimator.estimate(List(20) { 60.0 }, floorBpm = null, template = template(), minutesAsleep = 200)
        assertTrue("a learned floor must be enough to answer", e is Estimate.Read)
    }

    @Test
    fun `tonight's own floor still wins over the learned one`() {
        val t = template()!!
        val hr = List(20) { 60.0 }
        val onTypical = LiveRemEstimator.estimate(hr, null, t, minutesAsleep = 200) as Estimate.Read
        // A floor well below the typical one raises the measured elevation, so the two must differ —
        // proving the fallback is a fallback and not the value being used all along.
        val onMeasured = LiveRemEstimator.estimate(hr, t.typicalFloorBpm - 8.0, t, minutesAsleep = 200) as Estimate.Read
        assertTrue(onMeasured.confidence != onTypical.confidence)
    }

    @Test
    fun `no floor anywhere still means no answer`() {
        // The fallback must not become a licence to invent one: with neither a measured nor a learned
        // floor there is nothing to measure elevation against, and nothing is the right answer.
        val bare = template()!!.copy(typicalFloorBpm = 0.0)
        val e = LiveRemEstimator.estimate(List(20) { 60.0 }, floorBpm = null, template = bare, minutesAsleep = 200)
        assertTrue(e is Estimate.Unavailable)
    }

    @Test
    fun `a thin live stream means no answer`() {
        val thin = List(LiveRemEstimator.MIN_LIVE_SAMPLES - 1) { 60.0 }
        assertTrue(LiveRemEstimator.estimate(thin, 51.0, template(), minutesAsleep = 200) is Estimate.Unavailable)
    }

    // ── Discriminating ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `REM-like live HR reads higher than deep-like live HR`() {
        val t = template()
        val remLike = listOf(58.0, 63.0, 57.0, 64.0, 59.0, 62.0, 58.0, 63.0, 60.0, 64.0)
        val deepLike = List(10) { 52.0 + (it % 2) * 0.5 }

        val rem = LiveRemEstimator.estimate(remLike, 51.0, t, minutesAsleep = 200) as Estimate.Read
        val deep = LiveRemEstimator.estimate(deepLike, 51.0, t, minutesAsleep = 200) as Estimate.Read

        assertTrue(
            "REM-like input must score above deep-like input (${rem.confidence} vs ${deep.confidence})",
            rem.confidence > deep.confidence,
        )
        assertFalse("flat low HR must not read as REM", deep.inRem)
    }

    /**
     * The feature that actually separates REM from light sleep. Elevation alone cannot: a steady
     * elevated HR is light sleep, an UNSTEADY elevated HR is REM.
     */
    @Test
    fun `instability separates REM from merely-elevated sleep`() {
        val t = template()
        val elevatedButFlat = List(10) { 60.0 }
        val elevatedAndVariable = listOf(56.0, 64.0, 57.0, 65.0, 58.0, 63.0, 56.0, 64.0, 59.0, 65.0)

        val flat = LiveRemEstimator.estimate(elevatedButFlat, 51.0, t, minutesAsleep = 200) as Estimate.Read
        val variable = LiveRemEstimator.estimate(elevatedAndVariable, 51.0, t, minutesAsleep = 200) as Estimate.Read

        assertTrue(
            "same elevation, more variability must score higher (${variable.confidence} vs ${flat.confidence})",
            variable.confidence > flat.confidence,
        )
    }

    // ── Timing prior ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `never claims REM in the first twenty minutes`() {
        val t = template()
        val remLike = listOf(58.0, 63.0, 57.0, 64.0, 59.0, 62.0, 58.0, 63.0, 60.0, 64.0)
        val early = LiveRemEstimator.estimate(remLike, 51.0, t, minutesAsleep = 5) as Estimate.Read
        assertEquals("the prior must zero out REM this early", 0.0, early.confidence, 1e-9)
        assertFalse(early.inRem)
    }

    @Test
    fun `the same input scores higher later in the night`() {
        val t = template()
        val remLike = listOf(58.0, 63.0, 57.0, 64.0, 59.0, 62.0, 58.0, 63.0, 60.0, 64.0)
        val early = LiveRemEstimator.estimate(remLike, 51.0, t, minutesAsleep = 45) as Estimate.Read
        val late = LiveRemEstimator.estimate(remLike, 51.0, t, minutesAsleep = 300) as Estimate.Read
        assertTrue("REM lengthens toward morning", late.confidence > early.confidence)
    }

    @Test
    fun `the prior can only hold confidence down, never manufacture it`() {
        for (m in listOf(0, 30, 90, 200, 400, 1000)) {
            assertTrue(LiveRemEstimator.cyclePrior(m) <= 1.0)
            assertTrue(LiveRemEstimator.cyclePrior(m) >= 0.0)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `class axis returns zero when the classes coincide`() {
        // No information: must not produce a confident signed answer out of nothing.
        assertEquals(0.0, LiveRemEstimator.classAxis(5.0, 3.0, 3.0), 1e-9)
    }

    @Test
    fun `class axis is clamped and oriented`() {
        assertEquals(1.0, LiveRemEstimator.classAxis(100.0, 2.0, 8.0), 1e-9)
        assertEquals(-1.0, LiveRemEstimator.classAxis(-100.0, 2.0, 8.0), 1e-9)
        assertEquals(0.0, LiveRemEstimator.classAxis(5.0, 2.0, 8.0), 1e-9)
    }

    @Test
    fun `std dev uses the sample denominator and is zero for a flat series`() {
        assertEquals(0.0, LiveRemEstimator.stdDev(List(10) { 60.0 }), 1e-9)
        assertEquals(0.0, LiveRemEstimator.stdDev(listOf(60.0)), 1e-9)
        // n-1 denominator: [1,3] -> mean 2, ss 2, /1 -> sqrt(2)
        assertEquals(Math.sqrt(2.0), LiveRemEstimator.stdDev(listOf(1.0, 3.0)), 1e-9)
    }
}
