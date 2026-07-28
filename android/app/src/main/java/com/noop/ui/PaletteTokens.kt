package com.noop.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// MARK: - PaletteTokens — the per-scheme colour set behind `object Palette`
//
// Compose has no OS-dynamic colour (unlike iOS UIColor(light:dark:)), so the light theme is built
// the same way conceptually: ONE set of colour tokens, swapped wholesale per scheme. `Palette.active`
// is snapshot state, so every `Palette.X` read (in a composable OR a Canvas DrawScope) re-resolves
// automatically when the theme flips — ZERO call-site changes across the ~1,740 references.
//
// Dark values mirror StrandPalette.swift's dark; light values are the approved "Warm Paper" set
// (docs/superpowers/specs/2026-06-16-light-theme-design.md). Names/order match the Swift palette.

data class PaletteTokens(
    val surfaceBase: Color,
    val surfaceRaised: Color,
    val surfaceOverlay: Color,
    val surfaceInset: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val glowAmbient: Color,
    val accent: Color,
    val accentHover: Color,
    val accentMuted: Color,
    val focusRing: Color,
    val recovery000: Color,
    val recovery030: Color,
    val recovery055: Color,
    val recovery078: Color,
    val recovery100: Color,
    val strain000: Color,
    val strain033: Color,
    val strain066: Color,
    val strain100: Color,
    val sleepAwake: Color,
    val sleepLight: Color,
    val sleepDeep: Color,
    val sleepREM: Color,
    val zone1: Color,
    val zone2: Color,
    val zone3: Color,
    val zone4: Color,
    val zone5: Color,
    val statusPositive: Color,
    val statusWarning: Color,
    val statusCritical: Color,
    val metricCyan: Color,
    val metricPurple: Color,
    val metricAmber: Color,
    val metricRose: Color,
    val chargeColor: Color,
    val chargeDeep: Color,
    val chargeBright: Color,
    val chargeGlow: Color,
    val effortColor: Color,
    val effortDeep: Color,
    val effortBright: Color,
    val effortGlow: Color,
    val restColor: Color,
    val restDeep: Color,
    val restBright: Color,
    val restGlow: Color,
    val stressColor: Color,
    val stressDeep: Color,
    val stressBright: Color,
    val stressGlow: Color,
    val scenicCenter: Color,
    val scenicEdge: Color,
    val scenicStar: Color,
    val cardFillTop: Color,
    val cardFillBottom: Color,
    val gold: Color,
    val goldLight: Color,
    val goldDeep: Color,
    val goldDeepText: Color,
    val signalYellow: Color,
    val titaniumTop: Color,
    val titaniumMid: Color,
    val titaniumLow: Color,
    val titaniumDeep: Color,
    // The bright gauge-tip / sparkline-head core: white reads as a highlight on dark; on light it
    // would vanish into the white card, so it flips to a deep ink (crisp centre on the coloured bead).
    val tipCore: Color,
)

// One UI 8 dark scheme.
//
// The thing that makes One UI dark READ as One UI is CONTRAST BETWEEN LAYERS, not uniform darkness:
// a true-black canvas, cards that step up to a clearly lighter grey, and EMPHASISED controls that
// invert entirely — light grey fill with DARK text. Dials and sliders run a dark-grey track under a
// light fill. The first cut of this palette missed that: every layer sat within a few points of the
// others, so the app read as flat dark-on-dark. These values restore the steps.
//
// `accent` is a LIGHT grey on purpose: it is used as a FILL for buttons/pills, and `goldDeepText` is
// the near-black that sits on top of it. That pairing is the One UI primary-button idiom.
val DarkTokens = PaletteTokens(
    // Canvas is true black; each surface above it steps up a clear notch.
    surfaceBase = Color(0xFF000000), surfaceRaised = Color(0xFF1C1C1E), surfaceOverlay = Color(0xFF2C2C2E),
    surfaceInset = Color(0xFF141416), hairline = Color(0xFF39393D), hairlineStrong = Color(0xFF54545A),
    textPrimary = Color(0xFFFFFFFF), textSecondary = Color(0xFFB4B4BA), textTertiary = Color(0xFF7C7C84),
    glowAmbient = Color(0xFF1C1C1E),
    // Emphasised fill: light grey with near-black text on top (goldDeepText).
    accent = Color(0xFFE6E6EA), accentHover = Color(0xFFFFFFFF), accentMuted = Color(0xFF2C2C2E), focusRing = Color(0xFFE6E6EA),
    recovery000 = Color(0xFFE2574C), recovery030 = Color(0xFFE08A4A), recovery055 = Color(0xFFE0C455),
    recovery078 = Color(0xFFA6D97F), recovery100 = Color(0xFF5FD79A),
    strain000 = Color(0xFF4C7192), strain033 = Color(0xFF5F8CB2), strain066 = Color(0xFF77A8CE), strain100 = Color(0xFF9AC6E6),
    sleepAwake = Color(0xFF9E9EA6), sleepLight = Color(0xFF7594BE), sleepDeep = Color(0xFF465C84), sleepREM = Color(0xFF9DB4DC),
    zone1 = Color(0xFF7594BE), zone2 = Color(0xFF63A4B2), zone3 = Color(0xFFCBB86E), zone4 = Color(0xFFCE9663), zone5 = Color(0xFFCE7563),
    statusPositive = Color(0xFF5FD79A), statusWarning = Color(0xFFE0B25F), statusCritical = Color(0xFFE07563),
    metricCyan = Color(0xFF63A4B2), metricPurple = Color(0xFF9095BE), metricAmber = Color(0xFFCE9663), metricRose = Color(0xFFCE7584),
    chargeColor = Color(0xFF5FD79A), chargeDeep = Color(0xFF2F8F5F), chargeBright = Color(0xFF93E8BE), chargeGlow = Color(0xFF5FD79A),
    effortColor = Color(0xFF87B0D4), effortDeep = Color(0xFF4C7192), effortBright = Color(0xFFB0D0EA), effortGlow = Color(0xFF87B0D4),
    restColor = Color(0xFF97A3BE), restDeep = Color(0xFF5A6786), restBright = Color(0xFFB8C3D8), restGlow = Color(0xFF97A3BE),
    stressColor = Color(0xFFCE9663), stressDeep = Color(0xFF87B0D4), stressBright = Color(0xFFCE7563), stressGlow = Color(0xFFCE9663),
    scenicCenter = Color(0xFF1C1C1E), scenicEdge = Color(0xFF000000), scenicStar = Color(0xFF54545A),
    cardFillTop = Color(0xFF232326), cardFillBottom = Color(0xFF1C1C1E),
    // "gold" is a legacy token name kept for API stability — it is the same emphasised light grey.
    gold = Color(0xFFE6E6EA), goldLight = Color(0xFFFFFFFF), goldDeep = Color(0xFF9A9AA2),
    goldDeepText = Color(0xFF000000), signalYellow = Color(0xFFE0C455),
    titaniumTop = Color(0xFFFFFFFF), titaniumMid = Color(0xFFC8C8CE), titaniumLow = Color(0xFF8E8E96), titaniumDeep = Color(0xFF5A5A62),
    tipCore = Color(0xFFFFFFFF),
)


// The chart-style picker and the light/dark appearance picker both went with the Settings sections
// that were their only controls: this app has ONE fixed dark scheme and one data-colour ramp, so the
// modes, their persisted prefs and the Classic ramps are gone rather than left as unreachable state.
