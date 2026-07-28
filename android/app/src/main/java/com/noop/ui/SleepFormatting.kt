package com.noop.ui

import androidx.compose.ui.graphics.Color
import com.noop.analytics.SleepDebt
import com.noop.analytics.SleepDebtLedger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// MARK: - Formatting helpers (mirror SleepView.swift)

internal fun pct(minutes: Double, total: Double): Int =
    if (total > 0.0) (minutes / total * 100.0).roundToInt() else 0

internal fun pctValue(v: Double?): String = v?.let { "${it.roundToInt()}%" } ?: "—"

/** "+12% vs typical" / "−0.4 rpm vs typical" — the latest-vs-mean caption every tile carries. */
internal fun vsTypical(latest: Double?, typical: Double?, suffix: String, decimals: Int = 0): String {
    if (latest == null || typical == null || typical == 0.0) return "vs typical - "
    val diff = latest - typical
    val sign = if (diff >= 0) "+" else "−"
    val mag = abs(diff)
    val num = if (decimals == 0) "${mag.roundToInt()}" else String.format(java.util.Locale.US, "%.${decimals}f", mag)
    return "$sign$num$suffix vs typical"
}

// The debt TILE and the debt LEDGER measure DIFFERENT things and must not use the same words for them:
// the tile is LAST NIGHT's shortfall against need, the ledger is a rolling 14-night SIGNED balance where
// surplus nights cancel deficit ones. One short night under a fortnight of good ones is legitimately
// "Below need" last night AND "On target" over the window — but with both phrased identically and
// stacked on the same screen that reads as the app contradicting itself. The tile now names its scale.
//
// Both still share SleepDebt.ON_TARGET_BAND_MIN as the deadband, so they agree on how big a miss has
// to be before it counts at all.
internal fun debtCaption(debt: Double?): String {
    if (debt == null) return "vs need"
    return if (debt < SleepDebt.ON_TARGET_BAND_MIN) "Last night on target" else "Last night below need"
}

internal fun debtColor(debt: Double?): Color = when {
    debt == null -> Palette.textPrimary
    debt < SleepDebt.ON_TARGET_BAND_MIN -> Palette.statusPositive
    debt < SleepDebt.ON_TARGET_BAND_MIN * 2 -> Palette.statusWarning
    else -> Palette.statusCritical
}

// MARK: - Sleep-debt ledger formatting (mirror SleepView.swift)

/**
 * "≈2h 10m" magnitude headline — leading "≈" because it's an accumulated estimate. Reads
 * "On target" inside the deadband so a few stray minutes don't show as debt.
 */
internal fun debtHeadline(ledger: SleepDebtLedger): String =
    if (ledger.magnitudeMin < SleepDebt.ON_TARGET_BAND_MIN) "On target"
    else "≈${durationText(ledger.magnitudeMin)}"

/** Short tag beside the headline: sleep debt / surplus / balanced. */
internal fun debtTag(ledger: SleepDebtLedger): String = when {
    ledger.magnitudeMin < SleepDebt.ON_TARGET_BAND_MIN -> "balanced"
    ledger.isDebt -> "sleep debt"
    else -> "surplus"
}

/** Plain-English read of the running balance over the window. */
internal fun debtRead(ledger: SleepDebtLedger): String {
    val nights = ledger.nightCount
    val span = "the last $nights night${if (nights == 1) "" else "s"}"
    if (ledger.magnitudeMin < SleepDebt.ON_TARGET_BAND_MIN) {
        return "You're roughly on top of your sleep across $span. Slept minutes balance out against your need."
    }
    val mag = durationText(ledger.magnitudeMin)
    return if (ledger.isDebt) {
        "You've banked about $mag of sleep debt over $span. Surplus nights count back against it. An earlier night or two would clear it."
    } else {
        "You're carrying about $mag of surplus over $span. You've slept past your need on balance. Nicely ahead."
    }
}

/**
 * Color the balance by sign + size: surplus/within-band → positive green, modest debt →
 * warning, heavier debt → critical.
 */
internal fun debtBalanceColor(ledger: SleepDebtLedger): Color = when {
    ledger.magnitudeMin < SleepDebt.ON_TARGET_BAND_MIN || !ledger.isDebt -> Palette.statusPositive
    ledger.magnitudeMin < 180.0 -> Palette.statusWarning
    else -> Palette.statusCritical
}

/** Signed "+1h 20m" / "−2h 10m" / "0m" balance string. */
internal fun debtSigned(minutes: Double): String {
    if (abs(minutes) < 1.0) return "0m"
    val sign = if (minutes >= 0.0) "+" else "−"
    return "$sign${durationText(abs(minutes))}"
}

internal fun durationText(minutes: Double): String {
    val m = max(0, minutes.roundToInt())
    return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
}

internal fun List<Double>.sleepAverageOrNull(): Double? =
    if (isEmpty()) null else sum() / size
