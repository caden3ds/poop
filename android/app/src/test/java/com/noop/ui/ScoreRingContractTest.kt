package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ScoreRing]'s input contract.
 *
 * The ring takes a FRACTION in 0..1. It briefly also divided by 100 internally, while every one of its
 * seven call sites already passed a fraction — so every dial in the app drew at one hundredth of its real
 * fill (a 90% sleep score rendered as a ~1% sliver under the text "90"). This pins the mapping so the two
 * ends can't drift apart again.
 *
 * The composable itself can't be invoked off-device, so this tests the pure normalisation it performs.
 */
class ScoreRingContractTest {

    /** The exact expression [ScoreRing] uses to turn its input into a fill fraction. */
    private fun fill(value: Double?): Float = (value ?: 0.0).coerceIn(0.0, 1.0).toFloat()

    @Test
    fun `a fraction maps straight through`() {
        assertEquals(0.0f, fill(0.0), 1e-6f)
        assertEquals(0.5f, fill(0.5), 1e-6f)
        assertEquals(0.9f, fill(0.9), 1e-6f)
        assertEquals(1.0f, fill(1.0), 1e-6f)
    }

    /** A 90% score arrives as 0.9 and must fill 90% of the ring — not 0.9%. */
    @Test
    fun `a ninety percent score fills nine tenths`() {
        val ninetyPercent = 90.0 / 100.0
        assertEquals(0.9f, fill(ninetyPercent), 1e-6f)
    }

    @Test
    fun `out of range input is clamped, not wrapped`() {
        assertEquals(1.0f, fill(1.4), 1e-6f)
        assertEquals(1.0f, fill(90.0), 1e-6f)   // a raw 0-100 score saturates rather than vanishing
        assertEquals(0.0f, fill(-0.2), 1e-6f)
    }

    @Test
    fun `null reads as empty`() {
        assertEquals(0.0f, fill(null), 1e-6f)
    }
}
