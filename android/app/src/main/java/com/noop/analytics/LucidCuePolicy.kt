package com.noop.analytics

/*
 * LucidCuePolicy.kt — decides IF and HOW HARD to cue during REM, and when to stop for the night.
 *
 * Separated from [LiveRemEstimator] on purpose: the estimator answers a question about physiology,
 * this answers a question about restraint. Keeping them apart means the safety rules can be read,
 * argued with and tested without wading through signal processing.
 *
 * THE RULES, AND WHY EACH ONE EXISTS
 * ──────────────────────────────────
 *  • ENTRY DELAY — do not cue the moment REM is detected. A cue landing at the very start of a REM
 *    period tends to end it. [MIN_MINUTES_INTO_REM] lets the period establish itself first.
 *  • BUDGET — at most [MAX_CUES_PER_PERIOD] attempts in one REM period, and at most
 *    [MAX_CUES_PER_NIGHT] across the night. A trainer that nags is a trainer that wrecks sleep, and
 *    wrecked sleep is a worse outcome than a missed lucid dream.
 *  • SPACING — at least [MIN_SPACING_MIN] minutes between attempts, so a ramp cannot become a burst.
 *  • RAMP — the second attempt is more SALIENT, not stronger. The strap's haptic command carries a
 *    pattern id and a loop count with no amplitude field, so "louder" is not expressible; a REPEAT of
 *    the whole pattern is. See [CueStrength].
 *  • AROUSAL CUTOFF — after a cue, watch heart rate. A jump above the pre-cue baseline means the
 *    sleeper is surfacing, and the whole point is to cue a dream, not to wake someone. On that
 *    signal the night's remaining attempts in that period are abandoned.
 *
 * Pure: no clock, no IO. Time is always passed in as minutes/millis by the caller.
 */
object LucidCuePolicy {

    /** Minutes a REM period must have run before the first cue. */
    const val MIN_MINUTES_INTO_REM: Int = 10

    /** Upper bound on how deep into a REM period we will still open a first attempt. */
    const val MAX_MINUTES_INTO_REM: Int = 15

    /** Attempts allowed within one REM period. */
    const val MAX_CUES_PER_PERIOD: Int = 2

    /** Attempts allowed across the whole night. */
    const val MAX_CUES_PER_NIGHT: Int = 6

    /** Minimum gap between attempts (minutes). */
    const val MIN_SPACING_MIN: Int = 8

    /**
     * HR rise above the pre-cue baseline that counts as arousal (bpm). Chosen low deliberately: the
     * cost of standing down when the sleeper was fine is one missed cue; the cost of continuing when
     * they are waking is exactly the harm this feature must not do.
     */
    const val AROUSAL_RISE_BPM: Double = 6.0

    /** How long after a cue to watch for arousal (minutes). */
    const val AROUSAL_WATCH_MIN: Int = 3

    /**
     * Cue salience, as the number of TRIPLES to play.
     *
     * The strap exposes a pattern id and a loop count but no amplitude, so a stronger cue is not
     * expressible. It was tempting to spend the loop count here — but more loops makes a pulse LONGER,
     * not stronger, and a longer pulse is what stops three of them being felt as three. So each pulse
     * stays a single crisp tap and the ramp repeats the whole triple instead.
     */
    enum class CueStrength(val bursts: Int) {
        /** First attempt: one triple micro-pulse. */
        GENTLE(1),

        /** Second attempt in the same period, if the first passed unnoticed and REM held. */
        SALIENT(2),
    }

    /** Everything the policy needs to know about the night so far. */
    data class NightState(
        /** Cues already fired this REM period. */
        val cuesThisPeriod: Int = 0,
        /** Cues already fired tonight. */
        val cuesTonight: Int = 0,
        /** Minutes since the last cue, or null if none yet. */
        val minutesSinceLastCue: Int? = null,
        /** True once arousal was detected in the current REM period — latches until the period ends. */
        val arousalAbortedPeriod: Boolean = false,
    )

    /** The policy's verdict. */
    sealed interface Decision {
        /** Fire a cue at this salience. */
        data class Cue(val strength: CueStrength) : Decision

        /** Do nothing right now. [reason] is for the log/diagnostics, not for waking anyone up to read. */
        data class Hold(val reason: String) : Decision
    }

    /**
     * Decide whether to cue.
     *
     * @param inRem the estimator's current verdict.
     * @param minutesInRem how long the current REM period has been running.
     * @param state the night's running counters.
     * @param enabled the user's master switch. Checked HERE as well as at the call site so the policy
     *   can never be the thing that fires a cue the user switched off.
     */
    fun decide(
        inRem: Boolean,
        minutesInRem: Int,
        state: NightState,
        enabled: Boolean,
    ): Decision {
        if (!enabled) return Decision.Hold("Lucid cueing is off")
        if (!inRem) return Decision.Hold("Not in REM")
        if (state.arousalAbortedPeriod) return Decision.Hold("Stood down after arousal this period")
        if (state.cuesTonight >= MAX_CUES_PER_NIGHT) return Decision.Hold("Night budget spent")
        if (state.cuesThisPeriod >= MAX_CUES_PER_PERIOD) return Decision.Hold("Period budget spent")

        val since = state.minutesSinceLastCue
        if (since != null && since < MIN_SPACING_MIN) return Decision.Hold("Too soon after the last cue")

        // The first attempt sits in a window inside the REM period: late enough that the period is
        // established, early enough that it is likely still running when the cue lands.
        if (state.cuesThisPeriod == 0) {
            if (minutesInRem < MIN_MINUTES_INTO_REM) return Decision.Hold("REM period too young")
            if (minutesInRem > MAX_MINUTES_INTO_REM) return Decision.Hold("Past the cue window for this period")
            return Decision.Cue(CueStrength.GENTLE)
        }

        // A second attempt only happens if REM is STILL running and spacing has elapsed. The window
        // cap deliberately does not apply here: the point is that the period demonstrably held.
        return Decision.Cue(CueStrength.SALIENT)
    }

    /**
     * Did the sleeper stir after the last cue?
     *
     * @param baselineBpm mean HR over the minutes immediately BEFORE the cue.
     * @param postCueBpm HR samples since the cue.
     * @return true when the post-cue mean has risen [AROUSAL_RISE_BPM] or more above baseline.
     *
     * Too few post-cue samples returns false — "no evidence of arousal" — which is safe because the
     * spacing rule independently prevents another cue landing before the watch window has filled.
     */
    fun arousalDetected(baselineBpm: Double?, postCueBpm: List<Double>): Boolean {
        if (baselineBpm == null || baselineBpm <= 0.0) return false
        if (postCueBpm.size < 3) return false
        val post = postCueBpm.sum() / postCueBpm.size
        return post - baselineBpm >= AROUSAL_RISE_BPM
    }
}
