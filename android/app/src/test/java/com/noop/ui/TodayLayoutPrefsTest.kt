package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for the Today section-order persistence: default order, encode/decode round-trip,
 * reorder, and the never-hide "insert missing section at its default position" invariant. No Android
 * context — these are the pure functions the drag-to-arrange editor and the Today render rely on.
 *
 * The section set shrank when Synthesis / Your Cards / Start session were removed, so the fixtures below
 * use surviving sections only. The INVARIANTS are unchanged, and the stale-order cases still matter more
 * than ever: a layout saved by an older build will contain the removed raw keys, and decoding must drop
 * them and still yield every current section exactly once.
 */
class TodayLayoutPrefsTest {

    @Test
    fun emptyOrUnset_yieldsDefaultOrder() {
        assertEquals(TodaySection.defaultOrder, TodayLayoutPrefs.decodeOrder(null))
        assertEquals(TodaySection.defaultOrder, TodayLayoutPrefs.decodeOrder(""))
        assertEquals(TodaySection.defaultOrder, TodayLayoutPrefs.decodeOrder("   "))
    }

    @Test
    fun encodeDecode_roundTripsAReorderedList() {
        val reordered = listOf(
            TodaySection.HEART_RATE, TodaySection.HERO, TodaySection.KEY_METRICS,
            TodaySection.WORKOUTS, TodaySection.RECOVERY_VITALS, TodaySection.JOURNAL,
        )
        val encoded = TodayLayoutPrefs.encode(reordered)
        assertEquals("heartRate,hero,keyMetrics,workouts,recoveryVitals,journal", encoded)
        assertEquals(reordered, TodayLayoutPrefs.decodeOrder(encoded))
    }

    /**
     * An order saved by an OLDER build carries sections that no longer exist ("synthesis", "yourCards",
     * "liveSession"). They must be dropped silently, and every surviving section must still appear exactly
     * once — the upgrade path for anyone who had customised their Today layout before the cull.
     */
    @Test
    fun decode_dropsSectionsRemovedInThisFork_andKeepsEveryRemainingOne() {
        val legacy = "hero,liveSession,synthesis,keyMetrics,workouts,heartRate,recoveryVitals,yourCards,journal"
        val decoded = TodayLayoutPrefs.decodeOrder(legacy)
        assertEquals(TodaySection.entries.size, decoded.size)
        assertEquals(TodaySection.entries.toSet(), decoded.toSet())
        // The surviving sections keep the relative order the user had saved.
        assertEquals(
            listOf(
                TodaySection.HERO, TodaySection.KEY_METRICS, TodaySection.WORKOUTS,
                TodaySection.HEART_RATE, TodaySection.RECOVERY_VITALS, TodaySection.JOURNAL,
            ),
            decoded,
        )
    }

    @Test
    fun decode_insertsAnyMissingSectionAtItsDefaultPositionRelativeToSaved_neverHides() {
        // A saved order omitting WORKOUTS must still surface it, before the first saved section that
        // follows it in the default order.
        val partial = "heartRate,keyMetrics,recoveryVitals"
        val decoded = TodayLayoutPrefs.decodeOrder(partial)
        assertEquals(TodaySection.entries.size, decoded.size)
        assertEquals(
            listOf(
                // hero(0) and workouts(2) both precede heartRate(3) in default order, so both insert
                // before the saved heartRate, in default order among themselves:
                TodaySection.HERO, TodaySection.WORKOUTS,
                TodaySection.HEART_RATE, TodaySection.KEY_METRICS, TodaySection.RECOVERY_VITALS,
                // journal follows everything saved → appended:
                TodaySection.JOURNAL,
            ),
            decoded,
        )
    }

    @Test
    fun decode_dropsUnknownTokensAndCollapsesDuplicates() {
        val messy = "heartRate,BOGUS,heartRate, ,keyMetrics"
        val decoded = TodayLayoutPrefs.decodeOrder(messy)
        assertEquals(TodaySection.entries.size, decoded.size)
        assertEquals(TodaySection.entries.toSet(), decoded.toSet())
        // The saved heartRate→keyMetrics order survives the de-duplication.
        assertEquals(
            decoded.indexOf(TodaySection.HEART_RATE) < decoded.indexOf(TodaySection.KEY_METRICS),
            true,
        )
    }

    @Test
    fun allJunk_yieldsDefaultOrder() {
        assertEquals(TodaySection.defaultOrder, TodayLayoutPrefs.decodeOrder("nope,,zzz"))
    }

    /** defaultOrder must cover EVERY entry: the never-hide merge sorts by default index, so an entry
     *  missing from the default order could otherwise be dropped or mis-sorted. */
    @Test
    fun defaultOrderCoversEveryEntry() {
        assertEquals(TodaySection.entries.toSet(), TodaySection.defaultOrder.toSet())
        assertEquals(TodaySection.entries.size, TodaySection.defaultOrder.size)
    }

    @Test
    fun sectionRawKeysAreStableAndUnique() {
        val raws = TodaySection.entries.map { it.raw }
        assertEquals("raw keys must be unique (they're the persisted identity)", raws.size, raws.toSet().size)
        // Pin the exact wire strings — they are the persisted layout identity.
        assertEquals(
            listOf("hero", "keyMetrics", "workouts", "heartRate", "recoveryVitals", "journal"),
            raws,
        )
    }
}
