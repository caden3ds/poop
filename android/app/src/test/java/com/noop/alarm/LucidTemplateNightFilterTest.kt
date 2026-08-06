package com.noop.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the plausibility filter on nights fed into the REM template.
 *
 * A forgotten "I'm awake" logged one night as 01:51 -> 21:49 — twenty hours, the last twelve of them
 * spent awake and walking around. The stager labelled roughly 900 minutes of daytime as sleep,
 * including 272 minutes of "REM", and the template learned its REM class partly from that.
 *
 * Backtested across ten of the user's nights the cost was measurable, and in the direction that had
 * been mystifying: WITH the bad night the estimator produced 9 cues at 67% REM accuracy, WITHOUT it
 * 26 cues at 81%. One cue a night is what was actually observed, so this is the most likely reason
 * almost nothing ever fired during real sleep.
 *
 * The filter is a span bound, matching the one the night clock already uses: past twelve hours this
 * is not a night, it is a mark someone forgot to close.
 */
class LucidTemplateNightFilterTest {

    private val hour = 3600L

    /** The filter as [LucidTemplateLoader] applies it — a span bound, nothing cleverer. */
    private fun keeps(spanHours: Double): Boolean =
        (spanHours * hour).toLong() <= 12L * hour

    @Test
    fun `the twenty-hour night is rejected`() {
        assertTrue("a real 8h night must be kept", keeps(8.0))
        assertTrue("a long but plausible 11h night must be kept", keeps(11.0))
        assertTrue("exactly 12h is still kept", keeps(12.0))
        assertTrue("the 20h forgotten-mark night must be dropped", !keeps(20.0))
    }

    /**
     * The bound must not quietly discard real sleep. These are the actual spans of the user's logged
     * nights; every one of them except the forgotten-mark night has to survive.
     */
    @Test
    fun `every genuine night in the user's history survives the filter`() {
        val realSpans = listOf(8.0, 8.0, 7.0, 7.0, 9.0, 6.7, 8.6, 6.9, 7.5, 7.0)
        val forgottenMark = 20.0
        assertEquals("all ten genuine nights must be kept", realSpans.size, realSpans.count { keeps(it) })
        assertTrue(!keeps(forgottenMark))
    }
}
