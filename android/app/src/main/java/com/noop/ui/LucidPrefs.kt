package com.noop.ui

import android.content.Context

/**
 * LucidPrefs — settings + persisted night state for Lucid Dream Training.
 *
 * Follows the [InactivityPrefs] idiom: flat SharedPreferences keys in their own file, plus the small
 * amount of state the live cue path must carry across a service/process restart. The night counters
 * are persisted rather than held in memory precisely BECAUSE the phone may restart mid-night — an
 * in-memory budget would silently reset and allow a fresh six cues.
 *
 * All decision logic lives in the pure, unit-tested [com.noop.analytics.LucidCuePolicy] and
 * [com.noop.analytics.LiveRemEstimator]. This file is persistence only.
 */
object LucidPrefs {
    private const val FILE = "noop_lucid_prefs"

    /** Master switch for the night (REM cueing) half. Default OFF — this buzzes a sleeping person. */
    const val NIGHT_ENABLED = "lucid.nightEnabled"

    /** Master switch for the daytime reality-check half. Default OFF. */
    const val DAY_ENABLED = "lucid.dayEnabled"

    /** Reality checks per day. */
    const val DAY_CUES_PER_DAY = "lucid.dayCuesPerDay"

    /** Daytime window (local minutes-since-midnight) the reality checks may fire in. */
    const val DAY_START_MIN = "lucid.dayStartMinutes"
    const val DAY_END_MIN = "lucid.dayEndMinutes"

    // ── Persisted night state (survives a restart mid-night, so the budget cannot be reset by one) ──

    /** Local date (yyyy-MM-dd) the night counters below belong to; a new date resets them. */
    const val NIGHT_KEY = "lucid.nightKey"
    const val CUES_TONIGHT = "lucid.cuesTonight"
    const val CUES_THIS_PERIOD = "lucid.cuesThisPeriod"
    const val LAST_CUE_AT = "lucid.lastCueAt"
    const val PERIOD_AROUSAL_ABORTED = "lucid.periodArousalAborted"

    /** Local date the daytime cues below were scheduled for. */
    const val DAY_KEY = "lucid.dayKey"
    const val DAY_CUES_FIRED = "lucid.dayCuesFired"

    /** Local date the reality-check ALARMS were last armed for. Distinct from [DAY_KEY], which counts
     *  cues that fired: this records that a schedule exists at all, so a day where nothing ever fired
     *  can be detected and repaired rather than staying silent. */
    const val DAY_SCHEDULED_FOR = "lucid.dayScheduledFor"

    /** Cues skipped because the strap was not connected. The buzz is the whole feature, so a skip is a
     *  miss — counted separately so it can be surfaced instead of looking like a cue that happened. */
    const val DAY_CUES_SKIPPED = "lucid.dayCuesSkipped"

    // ── Last-night diagnostics ────────────────────────────────────────────────────────────────────
    //
    // The night half is a CHAIN — stream armed -> HR arriving -> template learned -> floor measured ->
    // confidence over threshold -> policy allows. A silent morning looks identical whichever link
    // broke, which is how three separate root causes each took a night to find. These record which
    // link the night actually reached, so the answer is readable in the morning instead of guessed.

    /** Local date these figures describe. */
    const val LAST_NIGHT_KEY = "lucid.lastNight.key"
    /** HR readings the runner actually received. Zero means the stream was never armed. */
    const val LAST_NIGHT_HR_TICKS = "lucid.lastNight.hrTicks"
    /** Nights in the learned template, or -1 when there was none (cold start). */
    const val LAST_NIGHT_TEMPLATE_NIGHTS = "lucid.lastNight.templateNights"
    /** Measured sleeping floor (bpm), or 0 when the night never accumulated enough coverage. */
    const val LAST_NIGHT_FLOOR_BPM = "lucid.lastNight.floorBpm"
    /** Highest REM confidence reached, as a percent 0..100. */
    const val LAST_NIGHT_MAX_CONFIDENCE = "lucid.lastNight.maxConfidencePct"
    /** The most recent reason the policy held, for the nights where everything else was fine. */
    const val LAST_NIGHT_HOLD_REASON = "lucid.lastNight.holdReason"
    /** Cues actually fired. */
    const val LAST_NIGHT_CUES = "lucid.lastNight.cues"

    /** Ticks where the app WANTED the realtime stream. */
    const val LAST_NIGHT_STREAM_WANTED = "lucid.lastNight.streamWanted"
    /** Ticks where the stream was actually ARMED on the strap. A large gap between this and
     *  [LAST_NIGHT_STREAM_WANTED] means the arming was refused, not that nothing asked. */
    const val LAST_NIGHT_STREAM_ARMED = "lucid.lastNight.streamArmed"
    /** Whether the link ever reached "bonded" — the gate the arming refuses on. */
    const val LAST_NIGHT_BONDED = "lucid.lastNight.bonded"

    /**
     * The bedtime this night ran against (epoch ms), or 0 if the user never marked one.
     *
     * Recorded per night so the morning summary can tell "you never told the app you were going to bed"
     * apart from "the strap never streamed" — both otherwise land on zero heart-rate ticks and read as a
     * malfunction. Only the first is the user's to fix.
     */
    const val LAST_NIGHT_BEDTIME_MS = "lucid.lastNight.bedtimeMs"

    /**
     * How many ticks the estimator actually produced a confidence for.
     *
     * The single fact that separates "the night ran and found no REM" from "the night never ran". Every
     * not-running cause — no template, no floor, a stream too thin — surfaces as a zero confidence, and
     * a zero confidence is indistinguishable from a real one. Counting the answers makes it decidable.
     */
    const val LAST_NIGHT_ESTIMATES = "lucid.lastNight.estimates"

    /**
     * Ticks the night ran with NO heart rate at all — the strap link down.
     *
     * Counted beside [LAST_NIGHT_HR_TICKS] rather than folded into it, because a night can be half real
     * and half blackout and the average hides exactly the half that matters. One night estimated
     * normally for 90 minutes and then sat at hr=0 for four and a half hours; the summary, reading only
     * the good half, would have called that a night that simply never got confident enough.
     */
    const val LAST_NIGHT_NO_HR_TICKS = "lucid.lastNight.noHrTicks"

    /**
     * Clock times of the cues that fired last night, comma-separated ("01:53,06:03").
     *
     * A cue that works is a cue you slept through, so "did it buzz me?" is the one question the morning
     * cannot answer from experience — and until now it could only be answered by reading the night log
     * off the device. The count alone is not enough: knowing a cue landed at 06:03 rather than 10:48
     * is what separates a working night from a forgotten wake mark.
     */
    const val LAST_NIGHT_CUE_TIMES = "lucid.lastNight.cueTimes"

    const val DEFAULT_DAY_CUES_PER_DAY = 4
    const val DEFAULT_DAY_START_MIN = 10 * 60   // 10:00
    const val DEFAULT_DAY_END_MIN = 21 * 60     // 21:00

    fun of(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun nightEnabled(context: Context): Boolean = of(context).getBoolean(NIGHT_ENABLED, false)
    fun setNightEnabled(context: Context, on: Boolean) {
        of(context).edit().putBoolean(NIGHT_ENABLED, on).apply()
    }

    fun dayEnabled(context: Context): Boolean = of(context).getBoolean(DAY_ENABLED, false)
    fun setDayEnabled(context: Context, on: Boolean) {
        of(context).edit().putBoolean(DAY_ENABLED, on).apply()
    }

    fun dayCuesPerDay(context: Context): Int =
        of(context).getInt(DAY_CUES_PER_DAY, DEFAULT_DAY_CUES_PER_DAY).coerceIn(1, 12)

    fun setDayCuesPerDay(context: Context, n: Int) {
        of(context).edit().putInt(DAY_CUES_PER_DAY, n.coerceIn(1, 12)).apply()
    }

    fun dayStartMin(context: Context): Int = of(context).getInt(DAY_START_MIN, DEFAULT_DAY_START_MIN)
    fun dayEndMin(context: Context): Int = of(context).getInt(DAY_END_MIN, DEFAULT_DAY_END_MIN)
    fun setDayWindow(context: Context, startMin: Int, endMin: Int) {
        of(context).edit()
            .putInt(DAY_START_MIN, startMin.coerceIn(0, 24 * 60))
            .putInt(DAY_END_MIN, endMin.coerceIn(0, 24 * 60))
            .apply()
    }
}
