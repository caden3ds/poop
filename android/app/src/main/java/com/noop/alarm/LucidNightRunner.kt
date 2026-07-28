package com.noop.alarm

import com.noop.analytics.LiveRemEstimator
import com.noop.analytics.LucidCuePolicy

/**
 * LucidNightRunner — the overnight state machine behind the lucid-dream cue.
 *
 * Sits between the live HR stream and the two pure deciders: it keeps the rolling HR window, tracks
 * how long the current REM period has run, and runs the post-cue arousal watch. It holds NO Android
 * dependency and reads no clock — `nowMs` is always supplied — so the whole cue path is testable on
 * the JVM. That matters more here than almost anywhere else in the app: this is the one feature that
 * deliberately buzzes a sleeping person, and it cannot be tested on hardware without spending a night
 * on each change.
 *
 * The night COUNTERS ([LucidCuePolicy.NightState]) are owned by the caller and persisted, not held
 * here — the phone can restart mid-night, and an in-memory budget would quietly reset and allow a
 * fresh set of cues. This class holds only what is cheap to rebuild.
 *
 * Every decision to actually fire belongs to [LucidCuePolicy]; every judgement about physiology
 * belongs to [LiveRemEstimator]. This class does neither, on purpose.
 */
class LucidNightRunner(
    /** How many live HR samples to retain for the feature window. */
    private val windowSize: Int = 40,
) {
    private val recentHr = ArrayDeque<Double>()

    /** When the current continuous REM stretch began (ms), or null when not currently in REM. */
    private var remPeriodStartMs: Long? = null

    /** HR mean over the window immediately before the last cue — the arousal baseline. */
    private var arousalBaseline: Double? = null

    /** When the last cue fired (ms), or null. Drives the AROUSAL WATCH, and is cleared once that
     *  short window closes. Deliberately NOT the spacing clock — see [lastCueFiredAtMs]. */
    private var lastCueAtMs: Long? = null

    /** When the last cue fired (ms), never cleared for the rest of the night. The spacing rule needs a
     *  clock that outlives the 3-minute arousal watch, otherwise "minutes since last cue" would reset
     *  to unknown after 3 minutes and the ramp could fire back-to-back. */
    private var lastCueFiredAtMs: Long? = null

    /** HR samples collected since the last cue, for the arousal test. */
    private val postCueHr = ArrayList<Double>()

    /** Minutes the current REM period has run at [nowMs], or null when not in REM. */
    fun minutesInRemAt(nowMs: Long): Int? =
        remPeriodStartMs?.let { ((nowMs - it) / 60_000L).toInt().coerceAtLeast(0) }

    /** What one tick decided, so the caller can act and log without re-deriving it. */
    data class Tick(
        /** Non-null when the caller should fire the cue at this salience. */
        val cue: LucidCuePolicy.CueStrength?,
        /** The updated night counters to persist. */
        val nextState: LucidCuePolicy.NightState,
        /** True when arousal was detected this tick and the period was stood down. */
        val arousalStoodDown: Boolean,
        /** Human-readable reason for a hold, for the strap log. Null when a cue fired. */
        val holdReason: String?,
        /** The estimator's confidence this tick, when it could answer. */
        val remConfidence: Double?,
    )

    /**
     * Fold in one live HR reading and decide what to do.
     *
     * @param hr live heart rate (bpm); non-positive readings are ignored as "no data".
     * @param floorBpm tonight's sleeping floor from [NightTroughTracker], or null if not yet known.
     * @param template the learned personal REM template, or null before enough scored nights.
     * @param minutesAsleep how long the sleeper has been asleep (for the cycle prior).
     * @param state the persisted night counters.
     * @param enabled the user's master switch.
     */
    fun onHeartRate(
        hr: Int,
        nowMs: Long,
        floorBpm: Int?,
        template: LiveRemEstimator.RemTemplate?,
        minutesAsleep: Int,
        state: LucidCuePolicy.NightState,
        enabled: Boolean,
    ): Tick {
        if (hr <= 0) {
            return Tick(null, state, arousalStoodDown = false, holdReason = "No heart rate", remConfidence = null)
        }
        recentHr.addLast(hr.toDouble())
        while (recentHr.size > windowSize) recentHr.removeFirst()

        // ── Arousal watch. Runs FIRST and unconditionally: if the sleeper is surfacing after the last
        // cue, that outranks anything the estimator has to say this tick.
        var arousalStoodDown = false
        var working = state
        val cuedAt = lastCueAtMs
        if (cuedAt != null) {
            val watchElapsedMin = (nowMs - cuedAt) / 60_000L
            if (watchElapsedMin <= LucidCuePolicy.AROUSAL_WATCH_MIN) {
                postCueHr.add(hr.toDouble())
                if (LucidCuePolicy.arousalDetected(arousalBaseline, postCueHr)) {
                    arousalStoodDown = true
                    working = working.copy(arousalAbortedPeriod = true)
                    lastCueAtMs = null
                    postCueHr.clear()
                }
            } else {
                // Watch window closed without a rise — the cue was tolerated.
                lastCueAtMs = null
                postCueHr.clear()
            }
        }

        val estimate = LiveRemEstimator.estimate(
            recentHr = recentHr.toList(),
            floorBpm = floorBpm?.toDouble(),
            template = template,
            minutesAsleep = minutesAsleep,
        )

        val inRem = (estimate as? LiveRemEstimator.Estimate.Read)?.inRem == true
        val confidence = (estimate as? LiveRemEstimator.Estimate.Read)?.confidence

        // ── REM period tracking. Being OUT of REM clears the per-period budget and the arousal latch
        // (both are scoped to a period, not to the night — the night budget is separate and persists).
        //
        // This clears on the STATE, not on the transition into it. Clearing only on a REM->non-REM edge
        // looked equivalent but wasn't: the counters are restored from prefs after a restart while
        // `remPeriodStartMs` starts null, so there is no edge to detect, and a restored "period budget
        // spent" would have blocked every remaining cue for the rest of the night.
        if (inRem) {
            if (remPeriodStartMs == null) remPeriodStartMs = nowMs
        } else {
            remPeriodStartMs = null
            if (working.cuesThisPeriod != 0 || working.arousalAbortedPeriod) {
                working = working.copy(cuesThisPeriod = 0, arousalAbortedPeriod = false)
            }
        }

        if (arousalStoodDown) {
            return Tick(null, working, arousalStoodDown = true, holdReason = "Arousal after cue — standing down", remConfidence = confidence)
        }

        // Age the spacing clock every tick. The policy compares this against MIN_SPACING_MIN, so a
        // stale value here would either block the ramp forever (never advancing) or let it fire
        // immediately (reset to null) — this derives it from the real elapsed time instead.
        working = working.copy(
            minutesSinceLastCue = lastCueFiredAtMs?.let { ((nowMs - it) / 60_000L).toInt().coerceAtLeast(0) },
        )

        val minutesInRem = minutesInRemAt(nowMs) ?: 0
        val decision = LucidCuePolicy.decide(
            inRem = inRem,
            minutesInRem = minutesInRem,
            state = working,
            enabled = enabled,
        )

        return when (decision) {
            is LucidCuePolicy.Decision.Cue -> {
                // Snapshot the pre-cue HR mean as the arousal baseline BEFORE the buzz lands.
                arousalBaseline = if (recentHr.isEmpty()) null else recentHr.sum() / recentHr.size
                lastCueAtMs = nowMs
                lastCueFiredAtMs = nowMs
                postCueHr.clear()
                Tick(
                    cue = decision.strength,
                    nextState = working.copy(
                        cuesThisPeriod = working.cuesThisPeriod + 1,
                        cuesTonight = working.cuesTonight + 1,
                        minutesSinceLastCue = 0,
                    ),
                    arousalStoodDown = false,
                    holdReason = null,
                    remConfidence = confidence,
                )
            }

            is LucidCuePolicy.Decision.Hold -> Tick(
                cue = null,
                nextState = working,
                arousalStoodDown = false,
                holdReason = decision.reason,
                remConfidence = confidence,
            )
        }
    }

    /** Drop all per-night state. Called when a new night starts. */
    fun reset() {
        recentHr.clear()
        remPeriodStartMs = null
        arousalBaseline = null
        lastCueAtMs = null
        lastCueFiredAtMs = null
        postCueHr.clear()
    }

    /**
     * Restore the spacing clock after a process restart, so the persisted budget and the persisted
     * "last cue at" agree. Without this a restart mid-night would keep the cue COUNT but forget when
     * the last one fired, and the ramp could fire immediately on the next REM tick.
     */
    fun restoreLastCueAt(atMs: Long?) {
        lastCueFiredAtMs = atMs
    }
}
