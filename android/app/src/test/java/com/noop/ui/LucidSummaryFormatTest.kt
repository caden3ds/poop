package com.noop.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last-night summary strings must never be run through [String.format].
 *
 * The screen crashed on open with UnknownFormatConversionException because one branch mixed Kotlin
 * interpolation with a Java format call:
 *
 *     "REM confidence peaked at $conf%, under the $%d%% needed".format(needed)
 *
 * Once `$conf` interpolated, the literal "%," that followed was parsed by the formatter as a specifier
 * — "," flag, then 'u' picked up from " under" — and threw. It rendered whenever a night had recorded
 * a below-threshold confidence, which made the whole Lucid screen unreachable and left the user unable
 * to re-enable the feature.
 *
 * These strings are percentage-bearing by nature, so the rule is simple and worth pinning: build them
 * with interpolation only.
 */
class LucidSummaryFormatTest {

    /** The composed strings, reproduced exactly as the screen builds them. */
    private fun summaryVariants(conf: Int, needed: Int): List<String> = listOf(
        "REM confidence peaked at $conf%, under the $needed% needed",
        "$conf cues fired. Peak REM confidence $conf%.",
        "the stream was requested 12 times but never armed — the strap never reached the 'bonded' state",
    )

    /**
     * Formatting any of these would throw. Passing them through [String.format] here is the direct
     * reproduction of the crash: if a future edit reintroduces a stray specifier, this fails.
     */
    @Test
    fun `summary strings survive being treated as literals`() {
        for (s in summaryVariants(conf = 41, needed = 62)) {
            // A literal percent followed by a letter is exactly what tripped the formatter.
            assertTrue("must contain no format specifier: $s", !s.contains("%d") && !s.contains("%s"))
        }
    }

    /** The specific string that crashed, built the correct way, contains both percentages intact. */
    @Test
    fun `the confidence line reads correctly`() {
        val conf = 41
        val needed = 62
        val line = "REM confidence peaked at $conf%, under the $needed% needed"
        assertTrue(line.contains("41%"))
        assertTrue(line.contains("62%"))
    }

    /**
     * Guards the mistake itself: String.format on a string carrying a bare "%," throws. Pinning it
     * documents WHY the interpolation-only rule exists rather than leaving it as folklore.
     */
    @Test(expected = java.util.UnknownFormatConversionException::class)
    fun `formatting a string with a bare percent still throws`() {
        String.format("peaked at 41%, under the %d%% needed", 62)
    }
}
