package com.noop.alarm

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [LucidRealityCheckScheduler.pickTimes] — slot selection for the daytime reality checks.
 *
 * Two properties matter and both are behavioural, not cosmetic:
 *  - slots stay INSIDE the user's chosen waking window (a cue outside it defeats the whole point of
 *    having a window, and at the wrong hour is just a buzz at a sleeping person), and
 *  - slots stay SPACED. Randomness is the feature, but two cues four minutes apart train nothing and
 *    read as a malfunction — so the picker returns fewer slots rather than bunching them.
 */
class LucidRealityCheckSchedulerTest {

    private val seeded = Random(20260727)

    @Test
    fun `slots stay inside the window`() {
        val start = 10 * 60
        val end = 21 * 60
        val times = LucidRealityCheckScheduler.pickTimes(start, end, 5, seeded)
        assertTrue("expected some slots", times.isNotEmpty())
        times.forEach {
            assertTrue("$it must be >= $start", it >= start)
            assertTrue("$it must be < $end", it < end)
        }
    }

    @Test
    fun `slots are spaced apart`() {
        val times = LucidRealityCheckScheduler.pickTimes(9 * 60, 22 * 60, 6, seeded)
        times.sorted().zipWithNext().forEach { (a, b) ->
            assertTrue(
                "slots $a and $b are closer than the minimum gap",
                abs(b - a) >= LucidRealityCheckScheduler.MIN_GAP_MIN,
            )
        }
    }

    @Test
    fun `slots come back in time order`() {
        val times = LucidRealityCheckScheduler.pickTimes(8 * 60, 22 * 60, 5, seeded)
        assertEquals(times.sorted(), times)
    }

    /**
     * A narrow window must yield FEWER cues, never a cluster. Asking for six checks inside two hours
     * cannot honour a 45-minute gap, so the picker gives back what fits.
     */
    @Test
    fun `a narrow window returns fewer slots rather than bunching them`() {
        val times = LucidRealityCheckScheduler.pickTimes(12 * 60, 14 * 60, 6, seeded)
        assertTrue("must not invent slots it cannot space", times.size < 6)
        times.sorted().zipWithNext().forEach { (a, b) ->
            assertTrue(abs(b - a) >= LucidRealityCheckScheduler.MIN_GAP_MIN)
        }
    }

    @Test
    fun `a window too small for even one gap yields nothing`() {
        assertTrue(LucidRealityCheckScheduler.pickTimes(12 * 60, 12 * 60 + 10, 3, seeded).isEmpty())
    }

    @Test
    fun `an inverted or empty window yields nothing`() {
        assertTrue(LucidRealityCheckScheduler.pickTimes(21 * 60, 9 * 60, 4, seeded).isEmpty())
        assertTrue(LucidRealityCheckScheduler.pickTimes(10 * 60, 10 * 60, 4, seeded).isEmpty())
    }

    @Test
    fun `zero or negative counts yield nothing`() {
        assertTrue(LucidRealityCheckScheduler.pickTimes(9 * 60, 21 * 60, 0, seeded).isEmpty())
        assertTrue(LucidRealityCheckScheduler.pickTimes(9 * 60, 21 * 60, -3, seeded).isEmpty())
    }

    /**
     * Every picked slot must end up ARMED, whether or not its clock time has already passed today.
     *
     * The scheduler used to drop past slots. That looked harmless and was not: the only thing that
     * re-arms the schedule is the receiver, which runs only when a cue fires — so a call that armed
     * nothing (all slots already past, e.g. enabling the feature in the evening, or the re-arm that
     * runs right after the LAST cue of the day) left the feature permanently silent. This pins the
     * count so the roll-to-tomorrow behaviour can't be lost again.
     */
    @Test
    fun `a full set of slots is produced regardless of the time of day`() {
        val start = 10 * 60
        val end = 21 * 60
        val times = LucidRealityCheckScheduler.pickTimes(start, end, 4, seeded)
        assertEquals("the window comfortably fits four spaced slots", 4, times.size)
        // Late evening: every one of these is in the past, so every one must roll forward rather than
        // being discarded — which is what the scheduler now does.
        val eveningNow = 22 * 60
        assertTrue("all slots are behind us at 22:00", times.all { it <= eveningNow })
    }

    /**
     * The fire-time window guard.
     *
     * These are non-wakeup alarms, so one that comes due while the phone dozes is delivered whenever the
     * device next wakes — possibly hours late and well outside the waking window. A reality check that
     * lands at midnight trains the cue against being asleep, which inverts the whole point, and buzzes
     * someone in bed. So the receiver re-checks the clock rather than trusting the schedule.
     */
    @Test
    fun `only fires inside the waking window`() {
        val start = 10 * 60
        val end = 21 * 60
        assertTrue(LucidRealityCheckScheduler.withinWindow(10 * 60, start, end))
        assertTrue(LucidRealityCheckScheduler.withinWindow(15 * 60, start, end))
        assertTrue("one minute before the end is still in", LucidRealityCheckScheduler.withinWindow(end - 1, start, end))

        assertFalse("the end minute itself is out", LucidRealityCheckScheduler.withinWindow(end, start, end))
        assertFalse("a deferred alarm at midnight must not buzz", LucidRealityCheckScheduler.withinWindow(0, start, end))
        assertFalse("nor one delivered late at night", LucidRealityCheckScheduler.withinWindow(23 * 60 + 30, start, end))
        assertFalse("nor one before the window opens", LucidRealityCheckScheduler.withinWindow(6 * 60, start, end))
    }

    /** Randomness is the point: the same window must not always produce the same times. */
    @Test
    fun `different seeds give different schedules`() {
        val a = LucidRealityCheckScheduler.pickTimes(9 * 60, 21 * 60, 4, Random(1))
        val b = LucidRealityCheckScheduler.pickTimes(9 * 60, 21 * 60, 4, Random(999))
        assertTrue("a predictable cue stops prompting a genuine check", a != b)
    }
}
