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
    /**
     * Feature window LENGTH IN MINUTES, not in samples.
     *
     * It was a flat 40-sample cap. The strap streams at roughly 1 Hz, so that was a ~40 SECOND window
     * while [LiveRemEstimator] documents and expects "roughly the last [LiveRemEstimator.LIVE_WINDOW_MIN]
     * minutes". Short-window variability is the feature that actually separates REM from light sleep, so
     * measuring it over 40 s instead of 5 min made the estimate far noisier than designed — and a noisy
     * estimate flickers across the threshold, which the REM-period tracking below then punishes.
     *
     * Time-based, so it self-adjusts if the sample rate changes instead of silently meaning something
     * different.
     */
    private val windowMinutes: Int = LiveRemEstimator.LIVE_WINDOW_MIN,
    /** Hard cap on retained samples, so an unexpectedly fast stream cannot grow this without bound. */
    private val maxSamples: Int = 600,
    /**
     * How long the estimate must stay OUT of REM before the period is declared over.
     *
     * Without this a single dipping tick reset the period clock, and the policy needs
     * [LucidCuePolicy.MIN_MINUTES_INTO_REM] of CONTINUOUS REM before it will cue — so on a real
     * (noisy) signal the period could be restarted forever and no cue would ever become due.
     */
    private val remExitGraceMs: Long = 90_000L,
    /**
     * How long the confidence is averaged over before it is compared to the cue threshold.
     *
     * The per-tick estimate is a good CLASSIFIER but a poor SEGMENTER. Backtested against seven of the
     * user's own staged nights it separates REM from non-REM with a mean AUC of 0.83 — genuinely
     * strong — yet consecutive five-minute readings swing across almost the whole range (78% → 7% → 4%
     * → 78% is a real sequence from one night). Thresholding that raw gave REM "periods" with a median
     * length of 2.9 minutes against a policy that needs [LucidCuePolicy.MIN_MINUTES_INTO_REM] of
     * continuous REM, so on a night with 30 detections not one cue could ever become due.
     *
     * Ten minutes is measured, not guessed: over those seven nights it is where cue accuracy peaks. At
     * the moment a cue would fire, 92% land inside a REM epoch and every night gets at least one —
     * against 67% and only three of seven nights for the raw signal. It also matches the timescale of
     * the thing being measured; a REM period is tens of minutes, so estimating it from a five-minute
     * window and no smoothing was always asking the signal for more resolution than it has.
     */
    private val confidenceSmoothingMs: Long = 10 * 60_000L,
    /**
     * Fraction of tonight's own smoothed readings that must sit BELOW a value for it to count as REM.
     *
     * The threshold is relative because the signal's scale is not stable between nights, while its
     * ability to rank REM against non-REM is. Measured across sixteen of the user's scored nights, the
     * estimator's AUC held at 0.67–0.81 throughout — it never stopped telling REM from non-REM — yet
     * the ABSOLUTE confidence drifted down as the learned classes converged, until on some nights REM
     * averaged 0.23 and a fixed 0.55 bar was unreachable. Five of sixteen nights produced no cue at all
     * for that reason, having nothing wrong with them.
     *
     * 0.80 is the physiology: REM is roughly a fifth to a quarter of a night, so the top fifth of
     * tonight's readings is where it should be. Backtested against a fixed 0.55 over those nights it
     * fires the same number of cues (34 against 33), lands 76% of them in REM against 61%, and reaches
     * fifteen nights instead of eleven.
     */
    private val adaptiveThresholdPercentile: Double = 0.80,
    /** Readings needed before the adaptive threshold is trusted; until then the fixed one applies. */
    private val adaptiveMinSamples: Int = 60,
    /** How often the adaptive threshold is recomputed. Longer than any cue window, so a period cannot
     *  be split by the threshold moving underneath it. */
    private val adaptiveRecomputeMs: Long = 30 * 60_000L,
) {

    /** One smoothed reading per minute of tonight, for the adaptive threshold's percentile. */
    private val nightConfidence = ArrayList<Double>()

    /** When the last percentile sample was taken, so this stays one-a-minute at any stream rate. */
    private var lastPercentileSampleMs: Long? = null

    /** The adaptive threshold in force, and when it was last recomputed. */
    private var adaptiveThreshold: Double? = null
    private var adaptiveThresholdAtMs: Long? = null

    /** (timestampMs, confidence) over the smoothing window — see [confidenceSmoothingMs]. */
    private val recentConfidence = ArrayDeque<Pair<Long, Double>>()
    /** (timestampMs, bpm), pruned by age rather than count. */
    private val recentHr = ArrayDeque<Pair<Long, Double>>()

    /** When the estimate last read REM; drives [remExitGraceMs]. */
    private var lastInRemMs: Long? = null

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
        recentHr.addLast(nowMs to hr.toDouble())
        // Prune by AGE, but never below the estimator's minimum. On a sparse stream (readings a minute
        // apart rather than a second) a strict 5-minute cut leaves fewer samples than the estimator will
        // accept, and it would refuse forever — trading a slightly staler window for an answer at all is
        // the better failure mode, and the estimator still applies its own floor on top.
        val cutoff = nowMs - windowMinutes * 60_000L
        while (recentHr.size > LiveRemEstimator.MIN_LIVE_SAMPLES && recentHr.first().first < cutoff) {
            recentHr.removeFirst()
        }
        while (recentHr.size > maxSamples) recentHr.removeFirst()

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
            recentHr = recentHr.map { it.second },
            floorBpm = floorBpm?.toDouble(),
            template = template,
            minutesAsleep = minutesAsleep,
        )

        // AWAKE stands the night down immediately, and clears the smoothing window with it.
        //
        // This must happen BEFORE the smoothing, and it must CLEAR rather than merely stop adding. The
        // window holds ten minutes of history at roughly one sample a second; feeding it a single
        // refusal — or nothing at all — leaves thousands of pre-waking samples still averaging above
        // the threshold, so a period opened while genuinely asleep survives right through waking up.
        // That is exactly how a cue fired at 08:06 against a heart rate of 113. Being awake is not a
        // low REM probability, it is a different state, so the period ends here.
        if (estimate is LiveRemEstimator.Estimate.Awake) {
            recentConfidence.clear()
            remPeriodStartMs = null
            lastInRemMs = null
            return Tick(
                cue = null,
                nextState = working,
                arousalStoodDown = false,
                holdReason = estimate.reason,
                remConfidence = null,
            )
        }

        // Smooth BEFORE thresholding. `Estimate.Read.inRem` is the estimator's own per-tick verdict; it
        // is deliberately ignored here in favour of the same comparison against a smoothed confidence,
        // so the threshold is applied once, to a signal that can actually hold a period together.
        val raw = (estimate as? LiveRemEstimator.Estimate.Read)?.confidence
        if (raw != null) {
            recentConfidence.addLast(nowMs to raw)
            val confCutoff = nowMs - confidenceSmoothingMs
            while (recentConfidence.size > 1 && recentConfidence.first().first < confCutoff) {
                recentConfidence.removeFirst()
            }
        }
        val confidence = if (recentConfidence.isEmpty()) null
                         else recentConfidence.sumOf { it.second } / recentConfidence.size
        // Sample tonight's distribution at most once a minute — the stream is ~1 Hz, and a percentile
        // over 30k points costs more than it tells us.
        if (confidence != null &&
            (lastPercentileSampleMs == null || nowMs - lastPercentileSampleMs!! >= 60_000L)
        ) {
            lastPercentileSampleMs = nowMs
            nightConfidence.add(confidence)
        }
        // Relative to tonight once there is an hour of it; the fixed bar until then. See
        // [adaptiveThresholdPercentile] for why an absolute threshold could not work.
        //
        // Recomputed on a slow cadence and HELD in between. A threshold that moved every tick was a
        // moving target: as more high readings accumulated it rose past readings it had already
        // accepted, ending the REM period and opening a new one — which handed back a fresh
        // per-period cue budget. A long REM stretch could then yield four cues where the policy allows
        // two. Holding it steady for longer than any single cue window closes that.
        // NEVER while a REM period is open. Changing the bar under a period that already cleared the
        // old one ends it and starts another, which hands back a fresh per-period cue budget — a long
        // stretch then yielded four cues where the policy allows two. The switch from the fixed bar to
        // the adaptive one is the same event and had the same effect. `remPeriodStartMs` still holds
        // the PREVIOUS tick's state here, which is exactly the question being asked.
        if (remPeriodStartMs == null &&
            nightConfidence.size >= adaptiveMinSamples &&
            (adaptiveThresholdAtMs == null || nowMs - adaptiveThresholdAtMs!! >= adaptiveRecomputeMs)
        ) {
            adaptiveThresholdAtMs = nowMs
            val sorted = nightConfidence.sorted()
            val idx = (sorted.size * adaptiveThresholdPercentile).toInt().coerceIn(0, sorted.size - 1)
            // Never let a flat night cue on noise: the fixed bar remains a floor the night must clear.
            adaptiveThreshold = maxOf(sorted[idx], LiveRemEstimator.CUE_THRESHOLD * 0.6)
        }
        val threshold = adaptiveThreshold ?: LiveRemEstimator.CUE_THRESHOLD
        val inRem = confidence != null && confidence >= threshold
        // Why the estimator could not answer, when it could not. This must not be thrown away: an
        // Unavailable estimate yields inRem=false exactly like a low-confidence Read does, so the policy
        // reports the generic "Not in REM" for both and the actual cause — no floor, no template, a thin
        // stream — never reaches the log. Nights were spent reading "Not in REM" for what was really
        // "the estimator never ran".
        val unavailableReason = (estimate as? LiveRemEstimator.Estimate.Unavailable)?.reason

        // ── REM period tracking. Being OUT of REM clears the per-period budget and the arousal latch
        // (both are scoped to a period, not to the night — the night budget is separate and persists).
        //
        // This clears on the STATE, not on the transition into it. Clearing only on a REM->non-REM edge
        // looked equivalent but wasn't: the counters are restored from prefs after a restart while
        // `remPeriodStartMs` starts null, so there is no edge to detect, and a restored "period budget
        // spent" would have blocked every remaining cue for the rest of the night.
        if (inRem) {
            lastInRemMs = nowMs
            if (remPeriodStartMs == null) remPeriodStartMs = nowMs
        } else {
            // End the period only after a SUSTAINED absence. A brief dip below the threshold is the
            // signal being noisy, not REM ending, and treating it as the latter restarts the clock the
            // policy is counting on.
            val since = lastInRemMs
            val sustainedExit = since == null || nowMs - since >= remExitGraceMs
            if (sustainedExit) {
                remPeriodStartMs = null
                lastInRemMs = null
                if (working.cuesThisPeriod != 0 || working.arousalAbortedPeriod) {
                    working = working.copy(cuesThisPeriod = 0, arousalAbortedPeriod = false)
                }
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
                arousalBaseline = if (recentHr.isEmpty()) null
                          else recentHr.sumOf { it.second } / recentHr.size
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

            // The estimator's own reason outranks "Not in REM" — see [unavailableReason]. Every other
            // hold (budget spent, too soon, stood down) is a real policy decision and keeps its wording.
            is LucidCuePolicy.Decision.Hold -> Tick(
                cue = null,
                nextState = working,
                arousalStoodDown = false,
                holdReason = if (decision.reason == "Not in REM" && unavailableReason != null) {
                    unavailableReason
                } else {
                    decision.reason
                },
                remConfidence = confidence,
            )
        }
    }

    /** Drop all per-night state. Called when a new night starts. */
    fun reset() {
        recentHr.clear()
        recentConfidence.clear()
        nightConfidence.clear()
        lastPercentileSampleMs = null
        adaptiveThreshold = null
        adaptiveThresholdAtMs = null
        remPeriodStartMs = null
        lastInRemMs = null
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
