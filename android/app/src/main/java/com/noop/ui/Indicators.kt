package com.noop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Indicators.kt — the flat One UI-style replacements for the old "Liquid Metal" primitives.
 *
 * What this replaces and why: the previous set simulated sloshing liquid in a vessel, driven by an
 * accelerometer feed and a per-frame physics step (LiquidSim / LiquidMotion / LiquidRender). It was the
 * app's signature look, but it animated constantly, held a sensor listener open on every screen showing a
 * score, and re-rendered every frame to say a number that hadn't changed. These draw the same
 * information as static geometry: no sensors, no per-frame clock, no physics.
 *
 * Signatures deliberately mirror the primitives they replace so the swap is a rename at each call site.
 * `animated` is accepted and ignored — kept so callers don't all need editing, and because "animated" no
 * longer means anything here. Nothing in this file loops.
 */

/** Sweep of the score ring, in degrees. Leaves a gap at the bottom so the track reads as a gauge
 *  rather than a full circle — the One UI progress idiom. */
private const val RING_SWEEP = 280f
private const val RING_START = 130f

/**
 * A score ring: a thick rounded arc over a faint track, filled clockwise by [value] (0..100).
 * Replaces the liquid-filled vessel. A null value draws the empty track only — an honest "no reading"
 * rather than a zero-filled gauge.
 */
@Composable
fun ScoreRing(
    /** Fill fraction in 0..1 (NOT a 0-100 score). null draws an empty ring with no animation. */
    value: Double?,
    tint: Color,
    animated: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // [value] is a FRACTION in 0..1, not a 0-100 score. This divided by 100 as well, so every dial in
    // the app drew at one hundredth of its real fill — a 90% sleep score rendered as a ~1% sliver.
    val target = (value ?: 0.0).coerceIn(0.0, 1.0).toFloat()
    val frac by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = if (value == null) 0 else 520, easing = Motion.easeOut),
        label = "scoreRingFill",
    )
    Canvas(modifier = modifier) {
        // The arc box MUST be square and centred. Drawing into the raw (possibly non-square) canvas
        // stretched the ring into an ellipse wherever its container was wider than tall — which is
        // exactly what the Rest dial did. Derive one diameter from the short edge and centre it.
        val stroke = size.minDimension * 0.085f
        val diameter = size.minDimension - stroke
        val arcSize = Size(diameter, diameter)
        val topLeft = Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f,
        )
        val cap = Stroke(width = stroke, cap = StrokeCap.Round)
        // Dark-grey track under a light fill — the One UI dial idiom. `hairline` is the faint
        // divider grey and disappeared under the ring; the track needs to read as its own layer.
        drawArc(
            color = Palette.surfaceOverlay,
            startAngle = RING_START, sweepAngle = RING_SWEEP, useCenter = false,
            topLeft = topLeft, size = arcSize, style = cap,
        )
        if (value != null && frac > 0f) {
            drawArc(
                color = tint,
                startAngle = RING_START, sweepAngle = RING_SWEEP * frac, useCenter = false,
                topLeft = topLeft, size = arcSize, style = cap,
            )
        }
    }
}

/**
 * A horizontal progress bar with fully-rounded ends — the pill One UI uses for inline levels.
 * Replaces the liquid tube. [frac] is 0..1.
 */
@Composable
fun MetricBar(
    frac: Double,
    tint: Color,
    height: Dp = 14.dp,
    animated: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val target = frac.coerceIn(0.0, 1.0).toFloat()
    val fill by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 420, easing = Motion.easeOut),
        label = "metricBarFill",
    )
    Canvas(modifier = modifier.height(height)) {
        val r = size.height / 2f
        // Dark-grey track, light fill — matching the dial and the One UI slider idiom.
        drawRoundRect(
            color = Palette.surfaceOverlay,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
        if (fill > 0f) {
            drawRoundRect(
                color = tint,
                size = Size(size.width * fill, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            )
        }
    }
}

/**
 * A plain polyline trace of [bpm] — the flat replacement for the animated "thread". No travelling
 * glint, no pulse: it just draws the series. Fewer than two points draws nothing (there is no line to
 * draw, and a single point would imply a trend we don't have).
 */
@Composable
fun MetricTrace(
    bpm: List<Double>,
    tint: Color = Palette.metricRose,
    height: Dp = 96.dp,
    animated: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.height(height)) {
        if (bpm.size < 2) return@Canvas
        val lo = bpm.min()
        val hi = bpm.max()
        val span = (hi - lo).takeIf { it > 1e-6 } ?: 1.0
        val stepX = size.width / (bpm.size - 1).toFloat()
        val stroke = 2.5f
        var prev = Offset(0f, size.height - ((bpm[0] - lo) / span).toFloat() * size.height)
        for (i in 1 until bpm.size) {
            val next = Offset(
                i * stepX,
                size.height - ((bpm[i] - lo) / span).toFloat() * size.height,
            )
            drawLine(color = tint, start = prev, end = next, strokeWidth = stroke, cap = StrokeCap.Round)
            prev = next
        }
    }
}

/**
 * The press feedback for a tappable card: a small scale-down plus a slight dim. Replaces the old
 * `liquidPress`, which did the same thing with liquid-themed naming. Honours reduce-motion by
 * collapsing the animation to an instant change.
 */
@Composable
fun Modifier.pressable(interactionSource: InteractionSource): Modifier {
    // A @Composable extension rather than `composed {}`: every call site is already in composition, and
    // `composed` allocates per composition and opts the chain out of Compose's modifier reuse/skipping —
    // paid on each of the ~20 tappable cards on every scroll frame.
    val reduced = rememberReduceMotion()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = if (reduced) tween(0) else tween(durationMillis = 160, easing = Motion.easeOut),
        label = "pressableScale",
    )
    return this.scale(scale)
}

// MARK: - Count-up numbers
//
// Rebuilt here after the liquid primitives were deleted (they lived in the same file). This is the one
// piece of that set worth keeping: it is plain number animation — a value tweens to its new figure — with
// no physics, no sensors and no per-frame clock. Reduce-motion collapses it to an instant change.

/**
 * A number that animates to [value] and renders through [format]. The label is derived from the FORMATTED
 * value, so a count-up never shows more precision than the caller asked for.
 */
@Composable
fun CountUpText(
    value: Double,
    format: (Double) -> String,
    style: TextStyle = NoopType.number(26f),
    color: Color = Palette.textPrimary,
    modifier: Modifier = Modifier,
) {
    val reduced = rememberReduceMotion()
    val animated by animateFloatAsState(
        targetValue = value.toFloat(),
        animationSpec = if (reduced) tween(0) else tween(durationMillis = 620, easing = Motion.easeOut),
        label = "countUp",
    )
    Text(text = format(animated.toDouble()), style = style, color = color, modifier = modifier)
}

/** A whole-number count-up — the common case ([CountUpText] with a rounding formatter). */
@Composable
fun CountUpNumber(
    value: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = NoopType.number(26f),
    color: Color = Palette.textPrimary,
) {
    CountUpText(
        value = value,
        format = { "${Math.round(it)}" },
        style = style,
        color = color,
        modifier = modifier,
    )
}
