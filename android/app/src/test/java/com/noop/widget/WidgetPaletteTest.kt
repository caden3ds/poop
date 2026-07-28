package com.noop.widget

import com.noop.analytics.RecoveryScorer
import com.noop.ui.DarkTokens
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget's colours and the XML chrome colours both live OUTSIDE Compose, so nothing at runtime
 * forces them to agree with the app's design tokens. That drift is not hypothetical: `surface_base` sat
 * on the pre-overhaul `#060A08` long after the canvas became true black, which showed as a colour flash
 * on every launch (the system paints the window background before the first Compose frame).
 *
 * These tests are the tripwire for that class of bug.
 */
class WidgetPaletteTest {

    /** `values/colors.xml`, located relative to the Gradle module dir the unit tests run from. */
    private fun colorsXml(): File {
        val candidates = listOf(
            File("src/main/res/values/colors.xml"),
            File("app/src/main/res/values/colors.xml"),
            File("android/app/src/main/res/values/colors.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("colors.xml not found from ${File(".").absolutePath}")
    }

    /** Reads `<color name="x">#AARRGGBB</color>` as a lowercase "aarrggbb" hex string. */
    private fun xmlColor(name: String): String {
        val text = colorsXml().readText()
        val m = Regex("""<color name="$name">#([0-9A-Fa-f]{6,8})</color>""").find(text)
            ?: error("no <color name=\"$name\"> in colors.xml")
        val raw = m.groupValues[1].lowercase(Locale.ROOT)
        return if (raw.length == 6) "ff$raw" else raw
    }

    /** The token's ARGB as the same lowercase "aarrggbb" string. */
    private fun tokenHex(argb: androidx.compose.ui.graphics.Color): String =
        String.format(Locale.ROOT, "%08x", argb.value.shr(32).toInt())

    @Test
    fun `xml chrome colours match the design tokens`() {
        // surface_base is the pre-first-frame window background — the one that shows as a launch flash.
        assertEquals("surface_base", tokenHex(DarkTokens.surfaceBase), xmlColor("surface_base"))
        assertEquals("surface_raised", tokenHex(DarkTokens.surfaceRaised), xmlColor("surface_raised"))
        assertEquals("accent", tokenHex(DarkTokens.accent), xmlColor("accent"))
        assertEquals("text_primary", tokenHex(DarkTokens.textPrimary), xmlColor("text_primary"))
        assertEquals("text_secondary", tokenHex(DarkTokens.textSecondary), xmlColor("text_secondary"))
    }

    /**
     * The widget must place a score in the SAME band the app does. [WidgetPalette.band] reads
     * [RecoveryScorer]'s cuts rather than repeating them, so this pins that they stay wired together
     * and that each band maps to a distinct colour.
     */
    @Test
    fun `widget bands agree with the recovery scorer`() {
        val red = WidgetPalette.band(0)
        val yellow = WidgetPalette.band(50)
        val green = WidgetPalette.band(100)

        assertTrue("bands must be visually distinct", red != yellow && yellow != green && red != green)

        // Boundaries: the scorer's cuts are the switch points, inclusive-below like RecoveryScorer.band.
        val redMax = RecoveryScorer.bandRedMax.toInt()
        val yellowMax = RecoveryScorer.bandYellowMax.toInt()
        assertEquals("just under the red cut is still red", red, WidgetPalette.band(redMax - 1))
        assertEquals("at the red cut it becomes yellow", yellow, WidgetPalette.band(redMax))
        assertEquals("just under the yellow cut is still yellow", yellow, WidgetPalette.band(yellowMax - 1))
        assertEquals("at the yellow cut it becomes green", green, WidgetPalette.band(yellowMax))
    }

    /** Effort must not read as a recovery band — it is a different metric on the same tile. */
    @Test
    fun `effort is distinct from every recovery band`() {
        val effort = WidgetPalette.effort
        listOf(0, 50, 100).forEach { score ->
            assertTrue("effort must differ from band($score)", effort != WidgetPalette.band(score))
        }
    }
}
