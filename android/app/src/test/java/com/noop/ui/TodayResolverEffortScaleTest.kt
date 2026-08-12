package com.noop.ui

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * #304 — [resolveTodayRow] (the pure logic behind AppViewModel._today) and the #313 Effort-gauge scale.
 *
 * Resolver: a non-UTC user who sleeps pre-midnight and wakes before the 04:00 logical rollover has the
 * just-finished night banked under the NEW local calendar day, while the logical key still points at
 * yesterday. The resolver must surface the LOCAL-day row in that window, yet keep the #144 anti-blank
 * guard (defer to the logical-day row, never blank, when no night is banked yet).
 *
 * Today's day-row resolution around the 04:00 boundary.
 *
 * This file also carried the Effort display-scale toggle's cases; the toggle was removed (Effort is
 * always shown 0–21) and they went with it. The resolver cases below are unrelated and still load-bearing.
 */
class TodayResolverEffortScaleTest {

    private fun day(key: String, sleepMin: Double?) =
        DailyMetric(deviceId = "my-whoop", day = key, totalSleepMin = sleepMin, recovery = 50.0, strain = 40.0)

    // --- #304 resolver ---------------------------------------------------------

    /** (a) Pre-04:00 non-UTC: prefer the banked local-calendar-day night over yesterday's logical row. */
    @Test
    fun preFourAm_prefersBankedLocalDayRow() {
        val logicalKey = "2026-06-13"
        val localKey = "2026-06-14"
        val rows = listOf(day(logicalKey, 400.0), day(localKey, 430.0))
        assertEquals(localKey, resolveTodayRow(rows, logicalKey, localKey)?.day)
    }

    /** (b) #144 guard: local-day row has no banked night → defer to the logical row, never blank. */
    @Test
    fun preFourAm_noBankedNight_defersToLogicalNeverBlank() {
        val logicalKey = "2026-06-13"
        val localKey = "2026-06-14"
        val rows = listOf(day(logicalKey, 400.0), day(localKey, null))
        val resolved = resolveTodayRow(rows, logicalKey, localKey)
        assertEquals(logicalKey, resolved?.day)
        assertNotNull("resolver must never blank when a logical-day row exists", resolved)
    }

    /** No local-day row at all → still falls back to the logical-day row. */
    @Test
    fun preFourAm_noLocalRow_defersToLogical() {
        val logicalKey = "2026-06-13"
        val localKey = "2026-06-14"
        val rows = listOf(day(logicalKey, 400.0))
        assertEquals(logicalKey, resolveTodayRow(rows, logicalKey, localKey)?.day)
    }

    /** Common daytime case (local == logical): plain logical lookup, no special-casing. */
    @Test
    fun daytime_localEqualsLogical_usesLogicalRow() {
        val key = "2026-06-14"
        val rows = listOf(day("2026-06-13", 400.0), day(key, 420.0))
        assertEquals(key, resolveTodayRow(rows, key, key)?.day)
    }

    // --- #313 Effort-gauge scale ----------------------------------------------



    /** The gauge fraction (value / scale-max) is scale-independent — a full-effort day fills both. */
}
