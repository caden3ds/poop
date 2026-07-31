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

        item { LucidLastNightCard() }
        item { LucidLiveTestCard(vm) }
    }
}

/** Last night's run, stated as the FIRST link that broke — only the earliest failure is actionable. */
@Composable
private fun LucidLastNightCard() {
    val context = LocalContext.current
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Overline("Last night")
            Text(
                lucidLastNightSummary(context),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
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
private fun lucidLastNightSummary(context: android.content.Context): String {
    val p = LucidPrefs.of(context)
    val night = p.getString(LucidPrefs.LAST_NIGHT_KEY, null)
        ?: return "Last night: nothing recorded yet."
    val ticks = p.getInt(LucidPrefs.LAST_NIGHT_HR_TICKS, 0)
    val templateNights = p.getInt(LucidPrefs.LAST_NIGHT_TEMPLATE_NIGHTS, -1)
    val floor = p.getInt(LucidPrefs.LAST_NIGHT_FLOOR_BPM, 0)
    val conf = p.getInt(LucidPrefs.LAST_NIGHT_MAX_CONFIDENCE, 0)
    val cues = p.getInt(LucidPrefs.LAST_NIGHT_CUES, 0)
    val hold = p.getString(LucidPrefs.LAST_NIGHT_HOLD_REASON, "").orEmpty()

    if (cues > 0) return "$night: $cues cue${if (cues == 1) "" else "s"} fired. Peak REM confidence $conf%."
    val why = when {
        ticks == 0 -> "no heart rate reached it — the live stream was never running"
        templateNights < 0 -> "no REM template yet — needs a scored night with both REM and non-REM"
        floor <= 0 -> "no sleeping floor measured — the night needs a few hours of heart rate"
        conf < (LiveRemEstimator.CUE_THRESHOLD * 100).toInt() ->
            "REM confidence peaked at $conf%, under the $%d%% needed".format(
                (LiveRemEstimator.CUE_THRESHOLD * 100).toInt(),
            )
        hold.isNotEmpty() -> "held: ${hold.lowercase()}"
        else -> "no cue was due"
    }
    return "$night: no cues — $why. ($ticks heart-rate readings.)"
}
