package com.noop.ui

import com.noop.analytics.HrvAnalyzer
import com.noop.analytics.SpotHrvReading
import com.noop.analytics.StressIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Live tab's autonomic console — live HRV + live stress off the realtime R-R buffer.
 *
 * The console deliberately owns no math of its own: HRV is [SpotHrvReading.compute] and stress is
 * [StressIndex.componentsRaw], both already pinned by their own tests. What IS new here is the band
 * mapping the console puts on the raw Baevsky SI, plus the contract the console relies on to stay
 * honest — a thin window must yield no number at all. Those are what this file pins.
 */
class LiveAutonomicTest {

    /** A clean, physiologically plausible R-R series (ms) long enough to clear the honesty gate. */
    private fun cleanSeries(n: Int = 40): List<Int> =
        (0 until n).map { 860 + (it % 7) * 6 - (it % 3) * 4 }

    @Test
    fun `si bands cover the scale in order`() {
        assertEquals("Relaxed", siBandLabel(0.0))
        assertEquals("Relaxed", siBandLabel(49.9))
        assertEquals("Balanced", siBandLabel(50.0))
        assertEquals("Balanced", siBandLabel(149.9))
        assertEquals("Elevated", siBandLabel(150.0))
        assertEquals("Elevated", siBandLabel(499.9))
        assertEquals("High", siBandLabel(500.0))
        assertEquals("High", siBandLabel(5_000.0))
    }

    @Test
    fun `band boundaries are closed below and open above`() {
        // Pinned explicitly because a drifting boundary would silently re-label a user's reading.
        val boundaries = listOf(50.0, 150.0, 500.0)
        for (b in boundaries) {
            assertTrue(
                "SI $b must not share a band with the value just below it",
                siBandLabel(b) != siBandLabel(b - 0.01),
            )
        }
    }

    /**
     * The console's core honesty guarantee: too few beats produces NO value, not an extrapolated one.
     * This is the behaviour that replaced the old opt-in snapshot dialog, so it has to hold on the live
     * path where the buffer starts empty and fills over time.
     */
    @Test
    fun `a thin window yields no hrv and no stress`() {
        val thin = listOf(850, 870, 840, 860, 855)
        assertTrue(SpotHrvReading.compute(thin) is SpotHrvReading.Outcome.Insufficient)
        assertNull(StressIndex.componentsRaw(thin.map { it.toDouble() }))

        assertTrue(SpotHrvReading.compute(emptyList()) is SpotHrvReading.Outcome.Insufficient)
        assertNull(StressIndex.componentsRaw(emptyList()))
    }

    @Test
    fun `a clean window yields both readouts`() {
        val rr = cleanSeries()
        val hrv = SpotHrvReading.compute(rr)
        assertTrue("a clean 40-beat window must read", hrv is SpotHrvReading.Outcome.Reading)
        assertTrue((hrv as SpotHrvReading.Outcome.Reading).rmssdMs > 0.0)

        val si = StressIndex.componentsRaw(rr.map { it.toDouble() })
        assertTrue("a clean 40-beat window must produce an SI", si != null && si.si > 0.0)
    }

    /**
     * The beat count the console shows next to a blank readout has to actually explain the blank, so the
     * insufficiency outcome must report against [HrvAnalyzer.MIN_BEATS] — the same floor that gates it.
     */
    @Test
    fun `insufficient outcome reports the real beat floor`() {
        val outcome = SpotHrvReading.compute(listOf(850, 870, 840)) as SpotHrvReading.Outcome.Insufficient
        assertEquals(HrvAnalyzer.MIN_BEATS, outcome.needed)
        assertTrue("clean count cannot exceed input", outcome.clean <= outcome.input)
    }
}
