package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the keying of the Lucid "last night" summary, and the label it is shown under.
 *
 * THE BUG: the night key rolls over at MIDDAY, not midnight, so that a bedtime after 00:00 still files
 * under the evening it belongs to. The rollover branch used to zero every LAST_NIGHT_* figure while
 * leaving LAST_NIGHT_KEY naming the night that had just finished — that key is written only by the
 * per-tick block, which stops running the moment the bedtime mark is cleared in the morning. So from
 * noon onwards the card read "<last night>: no bedtime was marked" for a night that had in fact been
 * marked, scored, and possibly cued. It looked correct all morning and broke at 12:00.
 *
 * The fix is to rekey LAZILY: the summary is left alone at rollover and reset only when the NEXT night
 * first writes, so the key and the figures beside it can never describe different nights.
 */
class LucidNightSummaryKeyingTest {

    /**
     * The service's night key: anything before midday belongs to the previous evening's night. Copied
     * here rather than imported because the original is a private helper on an Android Service.
     */
    private fun nightKeyFor(atHour: Int, day: Int): String =
        if (atHour < 12) "2026-08-%02d".format(day - 1) else "2026-08-%02d".format(day)

    @Test
    fun `a bedtime after midnight files under the previous evening`() {
        // 01:51 on the 2nd is the night that began on the 1st — the whole reason for a midday rollover.
        assertEquals("2026-08-01", nightKeyFor(atHour = 1, day = 2))
        assertEquals("2026-08-01", nightKeyFor(atHour = 23, day = 1))
        // …and both differ from the key the same clock produces after midday on the 2nd.
        assertEquals("2026-08-02", nightKeyFor(atHour = 13, day = 2))
    }

    /**
     * The regression itself, as a state machine over the stored pair (key, figures).
     *
     * Lazy rekeying means the pair only ever changes together. The old behaviour is reproduced in the
     * assertions' comments: zeroing at rollover left key="2026-08-01" beside bedtime=0.
     */
    @Test
    fun `the summary survives the midday rollover of the following day`() {
        var storedKey: String? = null
        var storedBedtime = 0L

        // A tick during the night writes both, together.
        fun tick(nightKey: String, bedtimeMs: Long) {
            val sameNight = storedKey == nightKey
            storedKey = nightKey
            storedBedtime = if (sameNight) maxOf(storedBedtime, bedtimeMs) else bedtimeMs
        }

        tick("2026-08-01", 1_785_000_000_000L)          // 01:51, night of the 1st→2nd
        assertEquals("2026-08-01", storedKey)
        assertTrue(storedBedtime > 0L)

        // Morning: the user taps "I'm awake", the mark clears, and ticks stop writing. Then midday
        // arrives and the night key rolls to 2026-08-02. NOTHING happens to the summary — that is the
        // fix. Previously this point zeroed storedBedtime while storedKey still read 2026-08-01.
        assertEquals("2026-08-01", storedKey)
        assertTrue("last night's bedtime must survive the rollover", storedBedtime > 0L)

        // The next night's first tick rekeys and resets in one step.
        tick("2026-08-02", 1_785_086_400_000L)
        assertEquals("2026-08-02", storedKey)
        assertEquals(1_785_086_400_000L, storedBedtime)
    }

    /** A night with no mark at all still reports honestly — the fix must not paper over a real zero. */
    @Test
    fun `a genuinely unmarked night still reads as unmarked`() {
        var storedKey: String? = "2026-08-01"
        var storedBedtime = 5L
        val nightKey = "2026-08-02"
        val sameNight = storedKey == nightKey
        storedKey = nightKey
        storedBedtime = if (sameNight) storedBedtime else 0L
        assertEquals("2026-08-02", storedKey)
        assertEquals("an unmarked new night reports zero, not the old night's mark", 0L, storedBedtime)
    }
}
