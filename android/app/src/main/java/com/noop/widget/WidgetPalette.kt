package com.noop.widget

import androidx.compose.ui.unit.Dp
import androidx.glance.unit.ColorProvider
import com.noop.analytics.RecoveryScorer
import com.noop.ui.DarkTokens
import com.noop.ui.Metrics

/**
 * The widget colour set, derived from the app's own [DarkTokens].
 *
 * Both widgets previously carried their own hardcoded navy/gold palette, duplicated between the two
 * files and unrelated to anything on screen — so a palette change moved the app and left the home-screen
 * tiles behind. Everything here reads a token instead, which is why the widgets track the app for free.
 *
 * A Glance widget lives outside Compose's snapshot state, so it cannot read `Palette.active`. It reads
 * the token OBJECT directly. That works because the app has exactly one scheme: [DarkTokens]. If a second
 * scheme is ever reintroduced, this is the one place that has to learn how to choose.
 *
 * Deliberately scheme-fixed: the app dropped light mode, and a widget that flipped to a light card while
 * every screen behind it stayed dark would read as a different app on the same home screen.
 */
internal object WidgetPalette {

    /** Card fill — the app's raised card surface, not the true-black canvas, so the tile reads as a card
     *  against whatever wallpaper is behind it. */
    val surface = ColorProvider(DarkTokens.surfaceRaised)

    val textPrimary = ColorProvider(DarkTokens.textPrimary)
    val textSecondary = ColorProvider(DarkTokens.textSecondary)

    /** Effort tint — the app's Effort colour, distinct from the recovery ramp so Effort never reads as
     *  another recovery band. */
    val effort = ColorProvider(DarkTokens.effortColor)

    /**
     * Recovery-band colour, keyed off [RecoveryScorer]'s OWN cuts rather than repeating 67 / 34 here —
     * the widget and the app must never disagree about which band a score is in. Charge and Rest both
     * read on the recovery band in the app, so they share this.
     */
    fun band(recovery: Int): ColorProvider = ColorProvider(
        when {
            recovery >= RecoveryScorer.bandYellowMax -> DarkTokens.recovery100
            recovery >= RecoveryScorer.bandRedMax -> DarkTokens.recovery055
            else -> DarkTokens.recovery000
        },
    )
}

/**
 * Widget geometry that has an in-app counterpart. Kept here for the same reason as [WidgetPalette]:
 * so the home-screen tile and the cards inside the app round the same way instead of drifting apart.
 *
 * (On API 31+ the launcher clips widgets to its own corner radius, so this is what actually shows on
 * API 26-30 and a no-op above it — harmless either way, and correct where it applies.)
 */
internal object WidgetMetrics {
    /** Card rounding — the app's card radius. */
    val cornerRadius: Dp = Metrics.cardRadius
}
