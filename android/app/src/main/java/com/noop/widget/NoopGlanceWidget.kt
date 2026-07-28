package com.noop.widget
import com.noop.ui.uiString

import androidx.compose.ui.res.stringResource
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.noop.R
import com.noop.ui.MainActivity
import java.text.DateFormat
import java.util.Date

/**
 * Home-screen widget: today's three top scores (Rest · Charge · Effort, Charge centred), with live HR
 * and strap battery at a glance (#516). Renders purely from the [WidgetSnapshotStore] SharedPreferences
 * snapshot — no BLE, no DB — so it costs nothing and survives process death. Tapping anywhere opens the
 * app. Each score is honest-null ("—") until POOP has scored it; it never fabricates a number.
 *
 * Colours come from [WidgetPalette], which derives them from the app's own design tokens — Glance
 * composes outside our theme, so it reads the token object directly rather than `Palette.active`.
 */
class NoopGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // A corrupt pref must degrade to the empty-state widget, not throw mid-provide.
        val snap = runCatching { WidgetSnapshotStore.load(context) }.getOrDefault(WidgetSnapshot())
        // No theme probe: the app has one fixed dark scheme, so the widget does too. It used to read a
        // "theme.appearance" pref that no longer exists, which left it able to render a light card while
        // every screen behind it stayed dark.
        provideContent { WidgetContent(snap) }
    }

    /** Defence-in-depth, NOT a crash fix: Glance 1.1.0's default already contains composition errors
     *  (it renders its built-in error layout; verified in bytecode while investigating #82 — which we
     *  could not reproduce). This override only swaps that generic layout for our own friendlier one.
     *  The widget heals on the next successful push. */
    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable,
    ) {
        runCatching {
            val rv = android.widget.RemoteViews(context.packageName, R.layout.noop_widget_error)
            android.appwidget.AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, rv)
        }
    }
}


@Composable
private fun WidgetContent(snap: WidgetSnapshot) {
    val surface = WidgetPalette.surface
    val textPrimary = WidgetPalette.textPrimary
    val textSecondary = WidgetPalette.textSecondary
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(surface)
            .cornerRadius(WidgetMetrics.cornerRadius)
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The three top scores in one row, Charge centred + enlarged (the app's hero order Rest · Charge ·
        // Effort). Each cell is honest-null until that score exists — never a fabricated number. (#516)
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.Bottom,
        ) {
            ScoreCell(
                label = uiString(R.string.l10n_noop_glance_widget_rest_cbaaa181),
                pct = snap.restPct,
                color = snap.restPct?.let { WidgetPalette.band(it) } ?: textSecondary,
                valueSize = 22.sp,
                textSecondary = textSecondary,
                modifier = GlanceModifier.defaultWeight(),
            )
            ScoreCell(
                label = uiString(R.string.l10n_noop_glance_widget_charge_49a8cb83),
                pct = snap.recoveryPct,
                color = snap.recoveryPct?.let { WidgetPalette.band(it) } ?: textSecondary,
                valueSize = 30.sp,
                textSecondary = textSecondary,
                modifier = GlanceModifier.defaultWeight(),
            )
            ScoreCell(
                label = uiString(R.string.l10n_noop_glance_widget_effort_660752e7),
                pct = snap.effortPct,
                color = snap.effortPct?.let { WidgetPalette.effort } ?: textSecondary,
                valueSize = 22.sp,
                textSecondary = textSecondary,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
        Spacer(modifier = GlanceModifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = snap.heartRate?.let { "♥ $it" } ?: "♥ - ",
                style = TextStyle(color = textPrimary, fontSize = 13.sp),
            )
            Spacer(modifier = GlanceModifier.width(10.dp))
            Text(
                text = snap.batteryPct?.let { "⚡ $it%" } ?: "⚡ - ",
                style = TextStyle(color = textPrimary, fontSize = 13.sp),
            )
        }
        Spacer(modifier = GlanceModifier.height(2.dp))
        Text(
            text = when {
                snap.connected -> "Connected"
                snap.updatedAtMs > 0L ->
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snap.updatedAtMs))
                else -> "Open POOP to connect"
            },
            style = TextStyle(color = textSecondary, fontSize = 11.sp),
        )
    }
}

/** One score column in the 2x2 widget: a small overline label over a big band-coloured "N%" (or a calm
 *  "—" in the secondary colour while that score is still null, so an unscored cell reads honestly rather
 *  than as a broken zero). (#516) */
@Composable
private fun ScoreCell(
    label: String,
    pct: Int?,
    color: ColorProvider,
    valueSize: androidx.compose.ui.unit.TextUnit,
    textSecondary: ColorProvider,
    modifier: GlanceModifier = GlanceModifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = TextStyle(color = textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium),
        )
        Text(
            text = pct?.let { "$it%" } ?: "—",
            style = TextStyle(color = color, fontSize = valueSize, fontWeight = FontWeight.Bold),
        )
    }
}
