package com.noop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

// MARK: - ProfileAvatar composable
//
// The one reusable avatar: the app's loop mark. A single [size] drives both the small header avatar
// (~28dp) and any larger use.

/**
 * The app's avatar glyph ([LoopMark]).
 *
 * There used to be a user-chosen photo behind this, downscaled into filesDir and held in snapshot
 * state. It was removed along with its picker and store: it was one more thing to get right (EXIF
 * orientation, downscaling, an extra prefs file) for a decoration nobody set. Token-only: a hairline
 * rim ties it to the rest of the chrome. Decorative by default — pass a [contentDescription] (e.g. on
 * the tappable header avatar) when it needs a spoken label.
 *
 * @param size diameter of the avatar.
 * @param modifier applied to the avatar box (e.g. the header's clickable/semantics wrapper).
 * @param contentDescription spoken label for the image; null keeps it decorative.
 */
@Composable
fun ProfileAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, Palette.hairline, CircleShape)
            // The label lived on the photo's Image. With the photo gone it belongs here, or the
            // tappable header avatar would lose its spoken name entirely.
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        LoopMark(modifier = Modifier.fillMaxSize())
    }
}

/**
 * The NOOP loop mark drawn as a fallback avatar: an OPEN ~80% recovery ring (round caps, starting at 12
 * o'clock, clockwise) in the recovery green with a solid WHITE centre core dot. Matches the iOS BrandMark
 * shape but in the Apple-Fitness green + white core. CLEAN/flat — no glow, no halo. Decorative.
 */
@Composable
private fun LoopMark(modifier: Modifier = Modifier) {
    val ring = Palette.statusPositive   // recovery / "on" green, a design token (no hardcoded hex)
    val core = Color.White
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.13f
        val radius = (size.minDimension - stroke) / 2f
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        val capStroke = Stroke(width = stroke, cap = StrokeCap.Round)
        // Open recovery-ring arc: ~80% (288°), −90° start (12 o'clock), clockwise.
        drawArc(
            color = ring,
            startAngle = -90f,
            sweepAngle = 288f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = capStroke,
        )
        // Solid white "core" dot at the centre.
        drawCircle(color = core, radius = stroke * 0.7f, center = center)
    }
}
