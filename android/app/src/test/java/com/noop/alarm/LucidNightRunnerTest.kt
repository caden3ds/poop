package com.noop.alarm

import com.noop.analytics.LiveRemEstimator
import com.noop.analytics.LucidCuePolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LucidNightRunner] over simulated nights.
 *
 * The unit tests for the estimator and the policy cover their pieces; this covers what only shows up
 * when they run together against a clock — the spacing clock advancing, the arousal watch latching,
 * and the per-period budget clearing when REM ends. Those are precisely the behaviours that cannot be
 * checked on hardware without sleeping through a night per change.
 */
class LucidNightRunnerTest {

    private val minute = 60_000L

    /** A template where REM is elevated and unsteady versus flat non-REM — the real pattern. */
    private val template: LiveRemEstimator.RemTemplate = LiveRemEstimator.learnTemplate(
        List(4) {
            LiveRemEstimator.NightSample(
                remHr = listOf(58.0, 62.0, 57.0, 63.0, 59.0, 64.0, 58.0, 62.0, 60.0, 63.0),
                nonRemHr = List(10) { 52.0 },
                floorBpm = 51.0,
            )
        },
    )!!

    /** HR values that read as REM: elevated over the floor and visibly variable. */
    private val remPattern = listOf(58, 63, 57, 64, 59, 62, 58, 63, 60, 64)

    /** HR values that read as deep: low and flat. */
    private val deepPattern = List(10) { 52 }

    /**
     * Drive [ticks] minutes of HR through the runner, one sample per minute.
     * Returns every cue that fired, as (minute, strength).
     */
    private fun run(
        runner: LucidNightRunner,
        pattern: List<Int>,
        startMinute: Int,
        ticks: Int,
        state: () -> LucidCuePolicy.NightState,
        setState: (LucidCuePolicy.NightState) -> Unit,
        minutesAsleepAt: (Int) -> Int = { 120 + it },
    ): List<Pair<Int, LucidCuePolicy.CueStrength>> {
        val fired = ArrayList<Pair<Int, LucidCuePolicy.CueStrength>>()
        for (i in 0 until ticks) {
            val m = startMinute + i
            val tick = runner.onHeartRate(
                hr = pattern[i % pattern.size],
                nowMs = m * minute,
                floorBpm = 51,
                template = template,
                minutesAsleep = minutesAsleepAt(i),
                state = state(),
                enabled = true,
            )
            setState(tick.nextState)
            tick.cue?.let { fired.add(m to it) }
        }
        return fired
    }

    @Test
    fun `fires a gentle cue once REM has run long enough, and not before`() {
        val runner = LucidNightRunner()
        var state = LucidCuePolicy.NightState()
        val fired = run(runner, remPattern, startMinute = 0, ticks = 25, state = { state }, setState = { state = it })

        assertTrue("expected exactly one cue in the first period, got $fired", fired.size == 1)
        val (atMinute, strength) = fired.first()
        assertTrue(
            "cue must not land before the REM period is established (at $atMinute)",
            atMinute >= LucidCuePolicy.MIN_MINUTES_INTO_REM,
        )
        assertTrue(strength == LucidCuePolicy.CueStrength.GENTLE)
    }

    @Test
    fun `the ramp respects spacing and escalates`() {
        val runner = LucidNightRunner()
        var state = LucidCuePolicy.NightState()
        // A long unbroken REM stretch: enough for the gentle cue, the spacing gap, then the salient one.
        val fired = run(runner, remPattern, startMinute = 0, ticks = 40, state = { state }, setState = { state = it })

        assertTrue("expected a ramp of two cues, got $fired", fired.size == 2)
        val (first, firstStrength) = fired[0]
        val (second, secondStrength) = fired[1]
        assertTrue(firstStrength == LucidCuePolicy.CueStrength.GENTLE)
        assertTrue(secondStrength == LucidCuePolicy.CueStrength.SALIENT)
        assertTrue(
            "the two cues must be at least ${LucidCuePolicy.MIN_SPACING_MIN} min apart (were ${second - first})",
            second - first >= LucidCuePolicy.MIN_SPACING_MIN,
        )
    }

    @Test
    fun `never exceeds the per-period budget however long REM runs`() {
        val runner = LucidNightRunner()
        var state = LucidCuePolicy.NightState()
        val fired = run(runner, remPattern, startMinute = 0, ticks = 240, state = { state }, setState = { state = it })
        assertTrue(
            "one REM period must never yield more than ${LucidCuePolicy.MAX_CUES_PER_PERIOD} cues, got ${fired.size}",
            fired.size <= LucidCuePolicy.MAX_CUES_PER_PERIOD,
        )
    }

    /**
     * The safety rule that matters most: a heart-rate rise after a cue means the sleeper is surfacing,
     * and the period must be abandoned rather than escalated into.
     */
    @Test
    fun `arousal after a cue stands the period down`() {
        val runner = LucidNightRunner()
        var state = LucidCuePolicy.NightState()

        // Advance minute by minute and STOP the instant the first cue fires — the arousal watch is only
        // open for AROUSAL_WATCH_MIN afterwards, so the stirring has to land inside that window.
        var cueMinute = -1
        var m = 0
        while (m < 60 && cueMinute < 0) {
            val tick = runner.onHeartRate(
                hr = remPattern[m % remPattern.size], nowMs = m * minute, floorBpm = 51,
                template = template, minutesAsleep = 120 + m, state = state, enabled = true,
            )
            state = tick.nextState
            if (tick.cue != null) cueMinute = m
            m++
        }
        assertTrue("expected a first cue within the hour", cueMinute >= 0)

        // HR climbs well above the pre-cue baseline, inside the watch window.
        var stoodDown = false
        for (i in 1..LucidCuePolicy.AROUSAL_WATCH_MIN) {
            val tick = runner.onHeartRate(
                hr = 80, nowMs = (cueMinute + i) * minute, floorBpm = 51,
                template = template, minutesAsleep = 140, state = state, enabled = true,
            )
            state = tick.nextState
            if (tick.arousalStoodDown) stoodDown = true
        }
        assertTrue("a clear post-cue HR rise must stand the period down", stoodDown)
        assertTrue("the stand-down must latch on the persisted state", state.arousalAbortedPeriod)

        // No further cue may fire while this REM period continues, even once spacing has elapsed.
        val after = run(runner, remPattern, cueMinute + 4, 40, { state }, { state = it })
        assertTrue("no cue may fire after arousal in the same period, got $after", after.isEmpty())
    }

    @Test
    fun `leaving REM clears the per-period budget and the arousal latch`() {
        val runner = LucidNightRunner()
        var state = LucidCuePolicy.NightState(
            cuesThisPeriod = 2, cuesTonight = 2, arousalAbortedPeriod = true,
        )
        // Drop out of REM for a stretch.
        run(runner, deepPattern, 0, 15, { state }, { state = it })
        assertTrue("per-period budget must clear when the period ends", state.cuesThisPeriod == 0)
        assertTrue("the arousal latch is scoped to its period", !state.arousalAbortedPeriod)
        assertTrue("the night budget must NOT clear", state.cuesTonight == 2)
    }

    /**
     * A brief dip below the threshold must NOT restart the REM period.
     *
     * The policy needs MIN_MINUTES_INTO_REM of CONTINUOUS REM before a cue is due. Without hysteresis a
     * single noisy tick reset that clock, so on a real signal the period could be restarted indefinitely
     * and no cue would ever become due — silent for a reason no diagnostic would have named.
     */
    @Test
    fun `a brief dip out of REM does not restart the period`() {
        val runner = LucidNightRunner()
        var state = LucidCuePolicy.NightState()

        // Establish REM, then inject ONE deep-looking minute partway in, then continue in REM.
        var fired = listOf<Pair<Int, LucidCuePolicy.CueStrength>>()
        var m = 0
        val collected = ArrayList<Pair<Int, LucidCuePolicy.CueStrength>>()
        while (m < 30) {
            val hr = if (m == 14) deepPattern[0] else remPattern[m % remPattern.size]
            val tick = runner.onHeartRate(
                hr = hr, nowMs = m * minute, floorBpm = 51,
                template = template, minutesAsleep = 120 + m, state = state, enabled = true,
            )
            state = tick.nextState
            tick.cue?.let { collected.add(m to it) }
            m++
        }
        fired = collected
        assertTrue("the dip must not prevent a cue entirely, got $fired", fired.isNotEmpty())
    }

    /** A SUSTAINED absence still ends the period and clears its budget. */
    @Test
    fun `a sustained exit from REM still ends the period`() {
        val runner = LucidNightRunner()
        var state = LucidCuePolicy.NightState(cuesThisPeriod = 2, cuesTonight = 2)
        // Well beyond the grace window.
        run(runner, deepPattern, 0, 20, { state }, { state = it })
        assertTrue("a real exit must clear the per-period budget", state.cuesThisPeriod == 0)
    }

    @Test
    fun `never cues without a template`() {
        val runner = LucidNightRunner()
        var state = LucidCuePolicy.NightState()
        for (i in 0 until 60) {
            val tick = runner.onHeartRate(
                hr = remPattern[i % remPattern.size], nowMs = i * minute, floorBpm = 51,
                template = null, minutesAsleep = 200, state = state, enabled = true,
            )
            state = tick.nextState
            assertNull("cold start must be silent", tick.cue)
        }
    }

    @Test
    fun `never cues without a measured sleep floor`() {
        val runner = LucidNightRunner()
        var state = LucidCuePolicy.NightState()
        for (i in 0 until 60) {
            val tick = runner.onHeartRate(
                hr = remPattern[i % remPattern.size], nowMs = i * minute, floorBpm = null,
                template = template, minutesAsleep = 200, state = state, enabled = true,
            )
            state = tick.nextState
            assertNull("no floor means no cue", tick.cue)
        }
    }

    @Test
    fun `never cues when disabled`() {
        val runner = LucidNightRunner()
        var state = LucidCuePolicy.NightState()
        for (i in 0 until 60) {
            val tick = runner.onHeartRate(
                hr = remPattern[i % remPattern.size], nowMs = i * minute, floorBpm = 51,
                template = template, minutesAsleep = 200, state = state, enabled = false,
            )
            state = tick.nextState
            assertNull(tick.cue)
        }
    }

    @Test
    fun `never cues in the first twenty minutes of sleep`() {
        val runner = LucidNightRunner()
        var state = LucidCuePolicy.NightState()
        val fired = run(
            runner, remPattern, 0, 19, { state }, { state = it },
            minutesAsleepAt = { it },   // just fell asleep
        )
        assertTrue("REM this early is not credible; no cue may fire, got $fired", fired.isEmpty())
    }

    @Test
    fun `restoring the spacing clock survives a restart`() {
        val runner = LucidNightRunner()
        // A cue fired one minute ago, per the persisted stamp; a fresh runner must honour the gap.
        runner.restoreLastCueAt(100 * minute)
        var state = LucidCuePolicy.NightState(cuesThisPeriod = 1, cuesTonight = 1)
        val fired = run(runner, remPattern, startMinute = 101, ticks = 5, state = { state }, setState = { state = it })
        assertTrue("a restart must not let the ramp fire immediately, got $fired", fired.isEmpty())
    }

    @Test
    fun `a missing heart rate is not treated as data`() {
        val runner = LucidNightRunner()
        val tick = runner.onHeartRate(
            hr = 0, nowMs = 0, floorBpm = 51, template = template,
            minutesAsleep = 200, state = LucidCuePolicy.NightState(), enabled = true,
        )
        assertNull(tick.cue)
        assertNotNull(tick.holdReason)
    }
}
