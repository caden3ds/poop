package com.noop.analytics

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/*
 * LiveRemEstimator.kt — a real-time "am I probably in REM right now?" estimate from live heart rate.
 *
 * WHY THIS EXISTS, AND WHAT IT IS NOT
 * ───────────────────────────────────
 * The morning hypnogram ([SleepStager] / [SleepStagerV2]) runs POST-HOC over a complete offloaded
 * night: it sees the whole series, both directions in time, and can revise its mind with Viterbi. None
 * of that is available at 03:00 with a live BLE stream and no future. So this is a SEPARATE, weaker
 * estimator, and it must never be confused with the scored hypnogram — it does not write a stage, it
 * does not feed recovery, and nothing downstream reads it. Its only consumer is the lucid-dream cue.
 *
 * The honest bar: "meaningfully better than a timer." Consumer lucid-dream devices typically fire a
 * fixed number of hours after bedtime and hope. Using the sleeper's own heart rate clears that bar
 * without pretending to be a hypnogram.
 *
 * METHOD: personal template matching
 * ─────────────────────────────────
 * Training data is free. Every night the app scores already yields labelled REM segments with HR
 * underneath them, so we can learn what THIS person's REM looks like rather than assuming a
 * population template. [RemTemplate] is that learned summary.
 *
 * Two HR-only features (the live stream is sparse, so anything richer is unavailable):
 *
 *   1. ELEVATION above tonight's own sleeping floor. REM sits higher than deep. The floor comes from
 *      the caller (`NightTroughTracker` already measures it) rather than from a population constant,
 *      so it self-calibrates to the night.
 *   2. INSTABILITY — short-window HR variability. This is the discriminating feature. Deep sleep is
 *      not merely low, it is FLAT (V1's deep gate keys on 11-minute HR flatness); REM is both higher
 *      and markedly less steady. Elevation alone would confuse REM with light sleep and with a brief
 *      arousal; instability is what separates them.
 *
 * Each feature is z-scored against the learned template and squashed; the two combine with a cycle
 * timing prior (REM lengthens toward morning — the same shape [SleepStagerV2] assumes) into a 0..1
 * confidence.
 *
 * COLD START IS SILENT. Below [MIN_TRAINING_NIGHTS] scored nights, or with no floor yet tonight,
 * [estimate] returns [Estimate.Unavailable] with a reason. It never guesses, because the consequence
 * of a guess here is buzzing a sleeping person.
 *
 * Pure: no clock, no IO, no Android. Every input is passed in.
 */
object LiveRemEstimator {

    /**
     * Scored nights required before any cue may fire. Below this the estimator stands down.
     *
     * Set to 1 so the feature is usable from a single scored night. That is a REAL reduction in
     * confidence, not a free win: with one night the template is that night's own statistics rather
     * than an average, so an atypical night (illness, alcohol, a late workout) becomes the whole model
     * of your REM. The protection that still holds is [RemTemplate.isDiscriminating] — a night whose
     * REM and non-REM look alike on heart rate is rejected outright, so a bad template refuses to cue
     * rather than cueing wrongly. Raise this once more nights are banked.
     */
    const val MIN_TRAINING_NIGHTS: Int = 1

    /** Samples needed in the live window before instability is meaningful. */
    const val MIN_LIVE_SAMPLES: Int = 8

    /** Live feature window (minutes). Long enough to measure variability, short enough to be current. */
    const val LIVE_WINDOW_MIN: Int = 5

    /**
     * Confidence at or above which the caller may consider cueing.
     *
     * Compared against a SMOOTHED confidence (see [com.noop.alarm.LucidNightRunner]'s smoothing
     * window), never a single tick — which is why 0.55 is stricter here than the old 0.62 was, not
     * looser. A raw tick could clear 0.62 on one noisy reading; sustaining a ten-minute mean above 0.55
     * takes real, held elevation.
     *
     * Calibrated, not chosen: backtested over seven of the user's own staged nights, this pairing puts
     * 92% of cue moments inside a REM epoch and gives every night at least one opportunity. The old
     * unsmoothed 0.62 managed 67% and reached only three of the seven nights at all.
     */
    const val CUE_THRESHOLD: Double = 0.55

    /**
     * What this person's REM looks like, learned from their own scored nights.
     *
     * Both figures are measured RELATIVE to each night's own sleeping floor / own scale, so they stay
     * valid as fitness, resting HR and season drift.
     */
    data class RemTemplate(
        /** Mean HR elevation above the night's floor during labelled REM (bpm). */
        val remElevationBpm: Double,
        /** Mean short-window HR standard deviation during labelled REM (bpm). */
        val remInstabilityBpm: Double,
        /** Mean HR elevation above the floor during labelled NON-REM sleep (bpm). */
        val nonRemElevationBpm: Double,
        /** Mean short-window HR standard deviation during labelled non-REM sleep (bpm). */
        val nonRemInstabilityBpm: Double,
        /** How many scored nights went into this template. */
        val nights: Int,
        /**
         * The person's TYPICAL sleeping floor across those nights (bpm), used when tonight's own floor
         * is not yet measurable.
         *
         * Tonight's floor is better and is always preferred. But it comes from a tracker needing hours
         * of continuous coverage inside one service lifetime, so it is unavailable early in the night
         * and after any restart — and with no floor the estimator refuses outright. A whole night was
         * lost to exactly that: floor=0 on every tick, so confidence was never computed at all rather
         * than merely low. A historical floor is the right stand-in because every elevation figure in
         * this template is itself measured relative to a night's own floor.
         */
        val typicalFloorBpm: Double,
    ) {
        /**
         * True when the template actually separates REM from non-REM. A template whose two classes sit
         * on top of each other carries no information, and matching against it would be noise dressed
         * up as a confidence — so the estimator refuses it rather than cueing on it.
         */
        val isDiscriminating: Boolean
            get() = nights >= MIN_TRAINING_NIGHTS &&
                (abs(remElevationBpm - nonRemElevationBpm) >= MIN_ELEVATION_SEPARATION ||
                    abs(remInstabilityBpm - nonRemInstabilityBpm) >= MIN_INSTABILITY_SEPARATION)
    }

    /**
     * How many REM-vs-non-REM class separations above the REM mean a feature may reach before the
     * reading is called wake rather than REM.
     *
     * Both feature axes CLAMP at +1 (see [classAxis]), so without a ceiling the scale has no top and
     * anything above the REM mean scores as maximally REM. Three separations is far outside what sleep
     * produces on either axis while leaving a strong REM burst room to breathe.
     */
    const val WAKE_CEILING_SEPARATIONS: Double = 3.0

    /** Minimum learned gap (bpm) between REM and non-REM on a feature for it to count as separating. */
    const val MIN_ELEVATION_SEPARATION: Double = 1.5
    const val MIN_INSTABILITY_SEPARATION: Double = 0.8

    /** The estimator's answer. */
    sealed interface Estimate {
        /**
         * A usable read. [confidence] is 0..1; [inRem] is simply confidence >= [CUE_THRESHOLD], stated
         * separately so callers can't accidentally invent their own threshold.
         */
        data class Read(
            val confidence: Double,
            val inRem: Boolean,
            val elevationBpm: Double,
            val instabilityBpm: Double,
        ) : Estimate

        /** Not enough to answer. [reason] is user-showable, and says what is missing. */
        data class Unavailable(val reason: String) : Estimate

        /**
         * The sleeper is not asleep. A POSITIVE finding, deliberately separate from [Unavailable].
         *
         * "No evidence" and "evidence of the opposite" must not share a branch. A missing floor or a
         * thin stream means the caller should wait; this means it should stand down and drop whatever
         * REM period it thought it had. Folding this into Unavailable is what let a cue fire at 08:06
         * one morning: the refusal added nothing to the caller's smoothing window, so the window kept
         * serving ten minutes of pre-waking confidence and the cue went off against a heart rate of 113.
         */
        data class Awake(val reason: String) : Estimate
    }

    /**
     * Build a [RemTemplate] from scored nights.
     *
     * @param nights one entry per scored night: the HR samples under REM-labelled epochs and under
     *   non-REM SLEEP epochs (awake epochs must be excluded by the caller — an awake stretch is high
     *   and unstable and would poison the REM class), plus that night's sleeping floor.
     *
     * Returns null when too few nights carry usable data. Nights missing either class are skipped
     * rather than half-counted.
     */
    fun learnTemplate(nights: List<NightSample>): RemTemplate? {
        val usable = nights.filter {
            it.remHr.size >= MIN_LIVE_SAMPLES && it.nonRemHr.size >= MIN_LIVE_SAMPLES && it.floorBpm > 0
        }
        if (usable.size < MIN_TRAINING_NIGHTS) return null

        val remElev = usable.map { mean(it.remHr) - it.floorBpm }
        val remInst = usable.map { stdDev(it.remHr) }
        val nonElev = usable.map { mean(it.nonRemHr) - it.floorBpm }
        val nonInst = usable.map { stdDev(it.nonRemHr) }

        // Median rather than mean: one unusually low night (a dropout reading, a cold night) should not
        // drag the reference the whole estimate is measured against.
        val floors = usable.map { it.floorBpm }.sorted()
        val typicalFloor = if (floors.size % 2 == 1) floors[floors.size / 2]
                           else (floors[floors.size / 2 - 1] + floors[floors.size / 2]) / 2.0

        return RemTemplate(
            remElevationBpm = remElev.average(),
            remInstabilityBpm = remInst.average(),
            nonRemElevationBpm = nonElev.average(),
            nonRemInstabilityBpm = nonInst.average(),
            nights = usable.size,
            typicalFloorBpm = typicalFloor,
        )
    }

    /** One scored night's contribution to the template. */
    data class NightSample(
        /** HR samples (bpm) under REM-labelled epochs. */
        val remHr: List<Double>,
        /** HR samples (bpm) under non-REM SLEEP epochs (awake excluded). */
        val nonRemHr: List<Double>,
        /** That night's sleeping HR floor (bpm). */
        val floorBpm: Double,
    )

    /**
     * Estimate whether the sleeper is in REM right now.
     *
     * @param recentHr live HR samples over roughly the last [LIVE_WINDOW_MIN] minutes, oldest first.
     * @param floorBpm tonight's sleeping HR floor, from `NightTroughTracker.troughBpm`. Null when the
     *   night has not yet accumulated enough coverage — which is itself a reason to stand down.
     * @param template the learned personal template, or null before enough nights exist.
     * @param minutesAsleep how long the sleeper has been asleep, for the cycle timing prior.
     */
    fun estimate(
        recentHr: List<Double>,
        floorBpm: Double?,
        template: RemTemplate?,
        minutesAsleep: Int,
    ): Estimate {
        if (template == null) {
            return Estimate.Unavailable(
                "Needs $MIN_TRAINING_NIGHTS scored " +
                    (if (MIN_TRAINING_NIGHTS == 1) "night" else "nights") +
                    " to learn your REM pattern.",
            )
        }
        if (!template.isDiscriminating) {
            return Estimate.Unavailable(
                "Your REM and deep sleep look too alike on heart rate alone so far.",
            )
        }
        // Prefer tonight's measured floor; fall back to the learned typical one rather than refusing.
        // Refusing was strictly worse: the tracker needs hours of unbroken coverage in a single service
        // lifetime, so on a night with any restart it never answers and the estimator produces nothing
        // at all — indistinguishable from the feature being broken.
        val floor = floorBpm?.takeIf { it > 0.0 } ?: template.typicalFloorBpm
        if (floor <= 0.0) {
            return Estimate.Unavailable("No sleeping floor known yet — needs a scored night.")
        }
        if (recentHr.size < MIN_LIVE_SAMPLES) {
            return Estimate.Unavailable("Waiting on a steady heart-rate stream.")
        }

        val elevation = mean(recentHr) - floor
        val instability = stdDev(recentHr)

        // Score each feature by which learned class it sits closer to, on a -1..+1 axis where +1 is
        // squarely REM. Using the midpoint between the two learned means as the decision point keeps
        // this self-calibrating: it needs no absolute bpm constants at all.
        // A reading far ABOVE the REM class is not more-REM-than-REM. It is being awake.
        //
        // [classAxis] clamps to +1, so the scale has no top: 45 bpm over the sleeping floor scores
        // exactly the same as the ~16 bpm that actually marks this sleeper's REM. That is not a
        // theoretical hole. On the first night a cue ever fired it fired twice at 10am, against a heart
        // rate of 85-102 over a floor of 41, because a bedtime mark had been left open — and being
        // awake outscored every genuine REM period of that night (61-78% against 54-55%).
        //
        // Removing automatic sleep detection removed the only thing that had implicitly bounded this,
        // so the ceiling has to live here. It is taken from the learned template rather than picked: the
        // two sleep classes sit (rem - nonRem) apart, and three of those separations above the REM mean
        // is far outside anything sleep produces while leaving a strong REM burst plenty of room. On
        // this user's template that is ~34 bpm over the floor, against real REM at 9-18 and an awake
        // wrist at 44-61.
        val elevGap = (template.remElevationBpm - template.nonRemElevationBpm).coerceAtLeast(1.0)
        if (elevation > template.remElevationBpm + WAKE_CEILING_SEPARATIONS * elevGap) {
            return Estimate.Awake("Heart rate is too high for sleep — this looks like being awake.")
        }
        // The SAME ceiling on instability, and it is the one that actually matters.
        //
        // Bounding elevation alone was half a fix. Waking up is a RAMP: heart rate climbs 50 -> 113 over
        // a couple of minutes, so the window's MEAN stays modest while its spread explodes — and
        // instability carries 0.6 of the score against elevation's 0.4. On the morning this was found,
        // confidence climbed 55% -> 63% -> 76% as the sleeper woke, sailing under the elevation ceiling
        // the whole way, and cued at 76%.
        val instGap = (template.remInstabilityBpm - template.nonRemInstabilityBpm).coerceAtLeast(1.0)
        if (instability > template.remInstabilityBpm + WAKE_CEILING_SEPARATIONS * instGap) {
            return Estimate.Awake("Heart rate is swinging too much for sleep — this looks like waking up.")
        }

        val elevScore = classAxis(elevation, template.nonRemElevationBpm, template.remElevationBpm)
        val instScore = classAxis(instability, template.nonRemInstabilityBpm, template.remInstabilityBpm)

        // Instability carries more weight: elevation alone cannot tell REM from light sleep, while the
        // "higher AND less steady" combination is what actually marks REM.
        val combined = 0.4 * elevScore + 0.6 * instScore

        // Timing prior: REM is rare in the first stretch of the night and lengthens toward morning.
        // Same shape SleepStagerV2.cyclePrior uses, expressed as a multiplier in 0..1 rather than a
        // log-space bonus, since this path has no Viterbi lattice to add into.
        val prior = cyclePrior(minutesAsleep)

        val confidence = (logistic(combined * 3.0) * prior).coerceIn(0.0, 1.0)
        return Estimate.Read(
            confidence = confidence,
            inRem = confidence >= CUE_THRESHOLD,
            elevationBpm = elevation,
            instabilityBpm = instability,
        )
    }

    /**
     * Where [v] sits on the axis running from the [nonRem] class mean (-1) to the [rem] class mean
     * (+1), clamped. When the two means coincide the axis carries no information and this returns 0
     * rather than dividing by ~zero and producing a confident answer out of nothing.
     */
    internal fun classAxis(v: Double, nonRem: Double, rem: Double): Double {
        val span = rem - nonRem
        if (abs(span) < 1e-6) return 0.0
        val mid = (rem + nonRem) / 2.0
        return ((v - mid) / (abs(span) / 2.0)).coerceIn(-1.0, 1.0) * (if (span < 0) -1.0 else 1.0)
    }

    /**
     * REM likelihood multiplier by time asleep. Near zero in the first ~20 minutes (REM that early is
     * not normal and cueing then would be firing into deep sleep), rising across the night. Capped at
     * 1.0 — the prior can only ever hold the estimate DOWN, never manufacture confidence.
     */
    internal fun cyclePrior(minutesAsleep: Int): Double = when {
        minutesAsleep < 20 -> 0.0
        minutesAsleep < 60 -> 0.35
        minutesAsleep < 120 -> 0.7
        minutesAsleep < 240 -> 0.9
        else -> 1.0
    }

    private fun logistic(x: Double): Double = 1.0 / (1.0 + exp(-x))

    private fun mean(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.sum() / xs.size

    /** Sample standard deviation (n-1), matching the denominator the HRV path uses. */
    internal fun stdDev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = mean(xs)
        val ss = xs.sumOf { (it - m) * (it - m) }
        return sqrt(ss / (xs.size - 1))
    }
}
