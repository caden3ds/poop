package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.alarm.LucidNightLog
import com.noop.alarm.LucidRealityCheckScheduler
import com.noop.alarm.LucidTemplateLoader
import com.noop.analytics.LiveRemEstimator
import com.noop.analytics.LucidCuePolicy
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Lucid dream training — its own screen, reached from Settings like Alarms or Notifications.
 *
 * It carries the two toggles, the cue test, the last-night diagnostic, and a LIVE TEST MODE that runs
 * the real estimator against the live heart-rate stream while you are awake.
 *
 * The test mode exists because this feature failed silently four nights running, each time for a
 * different reason, and each diagnosis cost a night. Every one of those causes — no stream, no
 * template, no floor — is visible here in seconds. It deliberately shows the estimator's INPUTS, not
 * just its verdict: a confidence number alone would not have identified any of them.
 */
@Composable
fun LucidScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var nightEnabled by remember { mutableStateOf(LucidPrefs.nightEnabled(context)) }
    var dayEnabled by remember { mutableStateOf(LucidPrefs.dayEnabled(context)) }
    var dayCues by remember { mutableStateOf(LucidPrefs.dayCuesPerDay(context)) }

    LazyScreenScaffold(
        title = "Lucid dream training",
        subtitle = "REM cues at night, reality checks by day.",
    ) {
        item {
            NoopCard(tint = Palette.restColor) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    LucidToggleRow(
                        title = "Cue me during REM",
                        detail = "Three long buzzes when Poop estimates you are in REM, at most " +
                            "${LucidCuePolicy.MAX_CUES_PER_NIGHT} a night and never within " +
                            "${LucidCuePolicy.MIN_SPACING_MIN} minutes of the last. Stops for the rest of " +
                            "that REM period if your heart rate says you stirred. Holds the heart-rate " +
                            "stream open overnight, which uses noticeably more battery. This will " +
                            "sometimes wake you.",
                        checked = nightEnabled,
                        onCheckedChange = {
                            nightEnabled = it
                            LucidPrefs.setNightEnabled(context, it)
                        },
                    )
                    Hairline()
                    LucidToggleRow(
                        title = "Daytime reality checks",
                        detail = "The same pattern at random times while you are awake, so you learn to " +
                            "recognise it. That is what gives the night cue something to trigger. " +
                            "Skipped when the strap is not connected.",
                        checked = dayEnabled,
                        onCheckedChange = {
                            dayEnabled = it
                            LucidPrefs.setDayEnabled(context, it)
                            if (it) LucidRealityCheckScheduler.schedule(context)
                            else LucidRealityCheckScheduler.cancel(context)
                        },
                    )
                    if (dayEnabled) {
                        LucidFormRow(label = "Checks per day") {
                            StepperField(
                                value = "$dayCues",
                                accessibility = "Reality checks per day",
                                onMinus = {
                                    dayCues = (dayCues - 1).coerceAtLeast(1)
                                    LucidPrefs.setDayCuesPerDay(context, dayCues)
                                    LucidRealityCheckScheduler.schedule(context)
                                },
                                onPlus = {
                                    dayCues = (dayCues + 1).coerceAtMost(12)
                                    LucidPrefs.setDayCuesPerDay(context, dayCues)
                                    LucidRealityCheckScheduler.schedule(context)
                                },
                            )
                        }
                    }
                    Hairline()
                    NoopButton(
                        text = "Test the cue now",
                        leadingIcon = Icons.Filled.Vibration,
                        kind = NoopButtonKind.Secondary,
                        fullWidth = true,
                        onClick = { vm.testLucidCue() },
                    )
                    Text(
                        "Three long buzzes with a clear pause between each, about four seconds in total.",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }
        }

        item { LucidLastNightCard(nightEnabled) }
        item { LucidNightLogCard() }
        item { LucidLiveTestCard(vm) }
    }
}

/** Last night's run, stated as the FIRST link that broke — only the earliest failure is actionable. */
@Composable
private fun LucidLastNightCard(nightEnabled: Boolean) {
    val context = LocalContext.current
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Overline("Last night")
                Spacer(Modifier.weight(1f))
                // State the switch here too: a night that recorded nothing because the feature was off
                // is the single most likely explanation, and it should not need interpreting.
                Text(
                    if (nightEnabled) "cueing on" else "cueing OFF",
                    style = NoopType.footnote,
                    color = if (nightEnabled) Palette.statusPositive else Palette.statusWarning,
                )
            }
            Text(
                lucidLastNightSummary(context),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
        }
    }
}

/**
 * The night timeline, straight off disk.
 *
 * The aggregate diagnostic says WHICH link broke; this says when and in what order, which is what
 * every diagnosis so far actually needed and had to be reconstructed without. Shown newest-first
 * because the interesting part of a night is usually its end, with a share action so it can leave the
 * phone without a cable.
 */
@Composable
private fun LucidNightLogCard() {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(expanded) { text = LucidNightLog.read(context) }

    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Overline("Night log")
                Spacer(Modifier.weight(1f))
                Text(
                    if (text.isBlank()) "empty" else "${text.lines().size} lines",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
            if (text.isBlank()) {
                Text(
                    "Nothing logged yet. Entries are written when the stream arms, when REM is entered " +
                        "or left, when a cue fires, and every few minutes as a heartbeat.",
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            } else {
                // Newest first: a night's ending is usually the part worth reading.
                val shown = text.trim().lines().reversed().let { if (expanded) it else it.take(12) }
                Text(
                    shown.joinToString(separator = System.lineSeparator()),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    NoopButton(
                        text = if (expanded) "Show less" else "Show all",
                        kind = NoopButtonKind.Tertiary,
                        onClick = { expanded = !expanded },
                    )
                    NoopButton(
                        text = "Share",
                        kind = NoopButtonKind.Tertiary,
                        onClick = {
                            runCatching {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Poop lucid night log")
                                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(send, "Share night log"),
                                )
                            }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    NoopButton(
                        text = "Clear",
                        kind = NoopButtonKind.Tertiary,
                        onClick = { LucidNightLog.clear(context); text = "" },
                    )
                }
            }
        }
    }
}

/**
 * Live test mode: the real estimator, running on the live stream, while awake.
 *
 * Holds the realtime HR stream open for as long as the card is on screen (released on exit), builds the
 * same rolling window the night runner uses, and shows every input alongside the verdict.
 *
 * Being explicit about what this can and cannot tell you: awake, your heart rate is far above your
 * sleeping floor and the cycle prior is not meaningful, so a HIGH confidence here does not mean the
 * estimator is right about REM. What it proves is that the PIPELINE works end to end — stream, window,
 * template, arithmetic — which is exactly what was broken on every failed night so far.
 */
@Composable
private fun LucidLiveTestCard(vm: AppViewModel) {
    val context = LocalContext.current
    val live by vm.live.collectAsStateWithLifecycle()

    var template by remember { mutableStateOf<LiveRemEstimator.RemTemplate?>(null) }
    var sessionsFound by remember { mutableStateOf(0) }
    var sessionsUsable by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }

    // The rolling window, kept here rather than reusing the night runner: this is an observation tool
    // and must never touch the state a real night depends on.
    val window = remember { ArrayDeque<Double>() }
    var windowSize by remember { mutableStateOf(0) }
    var floorSeen by remember { mutableStateOf<Double?>(null) }

    // Hold the stream open only while this card is shown.
    DisposableEffect(Unit) {
        vm.requestRealtimeHr()
        onDispose { vm.releaseRealtimeHr() }
    }

    LaunchedEffect(Unit) {
        val res = runCatching { LucidTemplateLoader.load(vm.repo, vm.activeStrapId) }.getOrNull()
        template = res?.template
        sessionsFound = res?.sessionsFound ?: 0
        sessionsUsable = res?.sessionsUsable ?: 0
        loaded = true
    }

    // Sample the live HR on a slow tick so the window matches the night path's one-reading-at-a-time
    // shape rather than every BLE notification.
    LaunchedEffect(Unit) {
        while (true) {
            val hr = live.heartRate
            if (hr != null && hr > 0) {
                window.addLast(hr.toDouble())
                while (window.size > 40) window.removeFirst()
                windowSize = window.size
                floorSeen = minOf(floorSeen ?: hr.toDouble(), hr.toDouble())
            }
            delay(2_000)
        }
    }

    val estimate = if (windowSize >= LiveRemEstimator.MIN_LIVE_SAMPLES) {
        LiveRemEstimator.estimate(
            recentHr = window.toList(),
            floorBpm = floorSeen,
            template = template,
            // Pin the prior OUT of the way: awake, "minutes asleep" is meaningless, and letting it
            // scale the result would make the readout say more about the clock than the signal.
            minutesAsleep = 300,
        )
    } else {
        null
    }

    NoopCard(tint = Palette.metricCyan) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Overline("Live test")
                Spacer(Modifier.weight(1f))
                Text(
                    if (live.connected) "strap connected" else "no strap",
                    style = NoopType.footnote,
                    color = if (live.connected) Palette.statusPositive else Palette.statusWarning,
                )
            }

            // 1. The template — the link that silently failed for four nights.
            Text("Your REM template", style = NoopType.subhead, color = Palette.textPrimary)
            val t = template
            Text(
                when {
                    !loaded -> "Loading…"
                    t == null && sessionsFound == 0 ->
                        "None yet — no scored nights with a hypnogram were found."
                    t == null ->
                        "None yet — found $sessionsFound scored night${if (sessionsFound == 1) "" else "s"}, " +
                            "but $sessionsUsable had enough REM and non-REM heart rate to learn from."
                    !t.isDiscriminating ->
                        "Learned from ${t.nights} night${if (t.nights == 1) "" else "s"}, but REM and " +
                            "non-REM look too alike to tell apart. No cue will fire on it."
                    else ->
                        "Learned from ${t.nights} night${if (t.nights == 1) "" else "s"}. " +
                            "REM sits ${t.remElevationBpm.roundToInt()} bpm over your floor at " +
                            "${"%.1f".format(t.remInstabilityBpm)} bpm variability; non-REM " +
                            "${t.nonRemElevationBpm.roundToInt()} bpm at " +
                            "${"%.1f".format(t.nonRemInstabilityBpm)}."
                },
                style = NoopType.footnote,
                color = Palette.textSecondary,
            )

            Hairline()

            // 2. The live inputs.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                LucidStat(Modifier.weight(1f), "Heart rate", live.heartRate?.let { "$it" } ?: "—")
                LucidStat(Modifier.weight(1f), "Window", "$windowSize/${LiveRemEstimator.MIN_LIVE_SAMPLES}")
                LucidStat(Modifier.weight(1f), "Low seen", floorSeen?.roundToInt()?.toString() ?: "—")
            }

            // 3. The verdict, with its own caveat attached.
            val read = estimate as? LiveRemEstimator.Estimate.Read
            val unavailable = estimate as? LiveRemEstimator.Estimate.Unavailable
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                LucidStat(
                    Modifier.weight(1f),
                    "Elevation",
                    read?.let { "${it.elevationBpm.roundToInt()} bpm" } ?: "—",
                )
                LucidStat(
                    Modifier.weight(1f),
                    "Variability",
                    read?.let { "%.1f".format(it.instabilityBpm) } ?: "—",
                )
                LucidStat(
                    Modifier.weight(1f),
                    "REM score",
                    read?.let { "${(it.confidence * 100).roundToInt()}%" } ?: "—",
                )
            }

            Text(
                when {
                    estimate == null ->
                        "Collecting heart rate… needs ${LiveRemEstimator.MIN_LIVE_SAMPLES} readings."
                    unavailable != null -> unavailable.reason
                    read != null && read.inRem ->
                        "Over the ${(LiveRemEstimator.CUE_THRESHOLD * 100).roundToInt()}% threshold. " +
                            "Awake that means nothing about REM — it means the whole pipeline is working."
                    else ->
                        "Under the ${(LiveRemEstimator.CUE_THRESHOLD * 100).roundToInt()}% threshold. " +
                            "Expected while awake and still; the numbers above are the real check."
                },
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun LucidStat(modifier: Modifier, label: String, value: String) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = NoopType.footnote, color = Palette.textTertiary)
        Text(value, style = NoopType.captionNumber, color = Palette.textPrimary)
    }
}

/** Local copies of the settings row primitives — each screen in this app keeps its own private set
 *  rather than sharing one, and matching that avoids clashing with the existing declarations. */
@Composable
private fun LucidToggleRow(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = NoopType.subhead, color = Palette.textPrimary)
            Text(detail, style = NoopType.footnote, color = Palette.textTertiary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.surfaceBase,
                checkedTrackColor = Palette.accent,
                uncheckedThumbColor = Palette.textSecondary,
                uncheckedTrackColor = Palette.surfaceInset,
                uncheckedBorderColor = Palette.hairline,
            ),
        )
    }
}

@Composable
private fun LucidFormRow(label: String, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, style = NoopType.subhead, color = Palette.textPrimary, modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
private fun Hairline() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(Modifier)
    ) {
        androidx.compose.material3.HorizontalDivider(color = Palette.hairline)
    }
}

/**
 * A plain-English read of last night's lucid diagnostic.
 *
 * Deliberately names the FIRST broken link rather than dumping every field: the chain is stream ->
 * heart rate -> template -> floor -> confidence -> policy, and only the earliest failure is
 * actionable. Each of those has been the real cause on a different night.
 */
/**
 * Human label for a night key ("2026-08-01") — the evening it began, so it spans into the next day.
 *
 * Rendered as a span ("Night of 1-2 Aug") rather than the bare key, with the marked bedtime appended
 * when one is known. A single date is ambiguous for anyone whose bedtime is after midnight, which is
 * exactly who this feature is for.
 */
private fun nightSpanLabel(nightKey: String, bedtimeMs: Long): String {
    val start = runCatching { java.time.LocalDate.parse(nightKey) }.getOrNull()
        ?: return nightKey
    val end = start.plusDays(1)
    val month = java.time.format.DateTimeFormatter.ofPattern("MMM")
    val span = if (start.month == end.month) {
        "Night of ${start.dayOfMonth}-${end.dayOfMonth} ${end.format(month)}"
    } else {
        "Night of ${start.dayOfMonth} ${start.format(month)} - ${end.dayOfMonth} ${end.format(month)}"
    }
    if (bedtimeMs <= 0L) return span
    val clock = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        .format(java.util.Date(bedtimeMs))
    return "$span (marked $clock)"
}

private fun lucidLastNightSummary(context: android.content.Context): String {
    val p = LucidPrefs.of(context)

    // "Nothing recorded" conflated three completely different situations — the switch being off, the
    // service never running, and the night running but reaching nothing. Only the first is the user's
    // to fix, so name it before anything else.
    if (!LucidPrefs.nightEnabled(context)) {
        return "Night cueing is switched off, so nothing was recorded. Turn it on above and it will " +
            "report on tomorrow morning."
    }
    val night = p.getString(LucidPrefs.LAST_NIGHT_KEY, null)
        ?: return "Night cueing is on, but no night has been recorded yet. The first reading is written " +
            "as soon as the background service sees a heart rate, so an empty result here means the " +
            "service was not running or the strap was not connected."
    val ticks = p.getInt(LucidPrefs.LAST_NIGHT_HR_TICKS, 0)
    val templateNights = p.getInt(LucidPrefs.LAST_NIGHT_TEMPLATE_NIGHTS, -1)
    val floor = p.getInt(LucidPrefs.LAST_NIGHT_FLOOR_BPM, 0)
    val conf = p.getInt(LucidPrefs.LAST_NIGHT_MAX_CONFIDENCE, 0)
    val cues = p.getInt(LucidPrefs.LAST_NIGHT_CUES, 0)
    val hold = p.getString(LucidPrefs.LAST_NIGHT_HOLD_REASON, "").orEmpty()
    val bedtimeMs = p.getLong(LucidPrefs.LAST_NIGHT_BEDTIME_MS, 0L)
    // The stored key is the date the EVENING began, so a 01:51 bedtime is filed under the previous
    // date — correct internally, and thoroughly confusing to read back when you habitually go to bed
    // after midnight ("2026-08-01" for a night you experienced as the 2nd). Name the span instead, and
    // use the actual marked bedtime when there is one.
    val label = nightSpanLabel(night, bedtimeMs)
    val estimates = p.getInt(LucidPrefs.LAST_NIGHT_ESTIMATES, 0)
    val noHr = p.getInt(LucidPrefs.LAST_NIGHT_NO_HR_TICKS, 0)

    if (cues > 0) return "$label: $cues cue${if (cues == 1) "" else "s"} fired. Peak REM confidence $conf%."
    val wanted = p.getInt(LucidPrefs.LAST_NIGHT_STREAM_WANTED, 0)
    val armed = p.getInt(LucidPrefs.LAST_NIGHT_STREAM_ARMED, 0)
    val bonded = p.getBoolean(LucidPrefs.LAST_NIGHT_BONDED, false)

    val why = when {
        // The night clock is the bedtime you mark on the Sleep tab, so an unmarked night runs nothing at
        // all. That is a deliberate tradeoff, and it looks identical to a broken strap from here — every
        // other "nothing happened" reason also lands on zero ticks — so it has to be named first.
        bedtimeMs <= 0L ->
            "no bedtime was marked — tap “Going to sleep” on the Sleep tab before bed " +
                "and the night runs from there"
        // Distinguish "nothing asked for the stream" from "the strap refused to arm it". On a 5/MG the
        // arming is gated on the link being 'bonded', and 'bonded' is only set once live heart rate is
        // already arriving — so a night can sit wanting the stream and never getting it.
        ticks == 0 && wanted > 0 && armed == 0 && !bonded ->
            "the stream was requested $wanted times but never armed — the strap never reached the " +
                "'bonded' state the arming waits on"
        ticks == 0 && wanted > 0 && armed == 0 ->
            "the stream was requested $wanted times but never armed"
        ticks == 0 && wanted == 0 ->
            "nothing asked for the live stream — the capture window may not have covered your night"
        ticks == 0 -> "no heart rate reached it — the live stream was never running"
        // These three explain a night the estimator never ANSWERED on. Gate them on that, or they
        // misreport a night that ran fine: the estimator now falls back to the template's learned floor,
        // so LAST_NIGHT_FLOOR_BPM can sit at 0 through a night that was estimating perfectly well, and
        // "no sleeping floor measured" would be flatly wrong.
        estimates == 0 && templateNights < 0 ->
            "no REM template yet — needs a scored night with both REM and non-REM"
        estimates == 0 && floor <= 0 ->
            "no sleeping floor, measured or learned — the night needs a few hours of heart rate"
        estimates == 0 -> "the estimator never returned a reading"
        // A night that ran well and then lost the strap must not be reported as merely unconfident. The
        // confidence figure is honest about the half it saw and silent about the half it did not, so
        // name the blackout first when it dominated the night.
        noHr > ticks -> {
            val needed = (LiveRemEstimator.CUE_THRESHOLD * 100).toInt()
            "the strap stopped reporting for most of the night — peak REM confidence was $conf% " +
                "(needs $needed%) over the part it could see"
        }
        conf < (LiveRemEstimator.CUE_THRESHOLD * 100).toInt() -> {
            // Pure interpolation, NO String.format. This line mixed the two and crashed the screen:
            // once "$conf" interpolated, the literal "%," that followed was parsed by the formatter as
            // a specifier ("," flag, then 'u' from " under") -> UnknownFormatConversionException. Any
            // string carrying a literal % must never be handed to format().
            val needed = (LiveRemEstimator.CUE_THRESHOLD * 100).toInt()
            "REM confidence peaked at $conf%, under the $needed% needed"
        }
        hold.isNotEmpty() -> "held: ${hold.lowercase()}"
        else -> "no cue was due"
    }
    return "$label: no cues — $why. ($ticks heart-rate readings.)"
}
