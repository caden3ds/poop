package com.noop.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fall-back-asleep re-buzz detector — the [SleepWindowWatcher]'s inverse twin. The detector
 * only ADVISES the caller to buzz the strap again; the wake itself already happened (the guaranteed
 * alarm fired), so nothing here can ever cost the user their wake — a silent watcher just means no
 * extra nudge. These tests assert the heuristic fires only on a genuinely SUSTAINED sink back to the
 * night's sleep floor, respects its budget/spacing/expiry bounds, and never guesses without a trough.
 */
class RebuzzWatcherTest {

    // Small, readable timings so the sustained-run arithmetic is obvious in each test.
    private fun watcher() = RebuzzWatcher(
        nearTroughBpm = 4,
        sustainMs = 60_000L,        // 1 min sustained dip
        watchWindowMs = 600_000L,   // 10 min watch
        maxRebuzzes = 2,
        minSpacingMs = 120_000L,    // 2 min between buzzes
    )

    private val t0 = 1_000_000L   // arbitrary epoch; all times offset from it

    @Test fun staysQuietWhenUnarmed() {
        val w = watcher()
        // Never armed → even a perfect sleep-floor stream must not fire.
        assertFalse(w.shouldRebuzz(48, t0))
        assertFalse(w.shouldRebuzz(48, t0 + 300_000L))
    }

    @Test fun armWithoutValidTroughIsANoOp() {
        val w = watcher()
        // No measured trough (strap never streamed inside the window) → we will not guess.
        w.arm(t0, Int.MAX_VALUE)
        assertFalse(w.isArmed)
        w.arm(t0, 0)
        assertFalse(w.isArmed)
        assertFalse(w.shouldRebuzz(48, t0 + 300_000L))
    }

    @Test fun firesAfterSustainedDipPastSpacing() {
        val w = watcher()
        w.arm(t0, 48)
        // Near-trough readings start 3 min after the alarm (past minSpacing); the run needs 1 min.
        assertFalse(w.shouldRebuzz(50, t0 + 180_000L))          // run starts
        assertFalse(w.shouldRebuzz(49, t0 + 210_000L))          // 30 s in — not sustained yet
        assertTrue(w.shouldRebuzz(50, t0 + 240_000L))           // 60 s in → re-buzz
    }

    @Test fun elevatedReadingBreaksTheRun() {
        val w = watcher()
        w.arm(t0, 48)
        assertFalse(w.shouldRebuzz(50, t0 + 180_000L))          // run starts
        assertFalse(w.shouldRebuzz(60, t0 + 210_000L))          // +12 over trough — awake, run broken
        assertFalse(w.shouldRebuzz(50, t0 + 240_000L))          // fresh run starts here...
        assertFalse(w.shouldRebuzz(50, t0 + 270_000L))          // ...30 s in
        assertTrue(w.shouldRebuzz(50, t0 + 300_000L))           // ...60 s in → re-buzz
    }

    @Test fun noLiveHrBreaksTheRun() {
        val w = watcher()
        w.arm(t0, 48)
        assertFalse(w.shouldRebuzz(50, t0 + 180_000L))
        // BLE dropout: with no signal we can't vouch the user is asleep — the run must not coast.
        assertFalse(w.shouldRebuzz(0, t0 + 210_000L))
        assertFalse(w.shouldRebuzz(50, t0 + 240_000L))          // run restarts
        assertFalse(w.shouldRebuzz(50, t0 + 270_000L))
        assertTrue(w.shouldRebuzz(50, t0 + 300_000L))
    }

    @Test fun spacingHoldsFromTheAlarmItself() {
        val w = watcher()
        w.arm(t0, 48)
        // A sustained dip completes at t0+90s, but the 2-min spacing from the ALARM isn't up yet.
        assertFalse(w.shouldRebuzz(50, t0 + 30_000L))
        assertFalse(w.shouldRebuzz(50, t0 + 60_000L))
        assertFalse(w.shouldRebuzz(50, t0 + 90_000L))           // sustained, but too soon after alarm
        assertTrue(w.shouldRebuzz(50, t0 + 121_000L))           // spacing satisfied → fire
    }

    @Test fun respectsBudgetThenDisarms() {
        val w = watcher()
        w.arm(t0, 48)
        // First re-buzz.
        assertFalse(w.shouldRebuzz(50, t0 + 180_000L))
        assertTrue(w.shouldRebuzz(50, t0 + 241_000L))
        // Second re-buzz: fresh sustained run + spacing from the first buzz.
        assertFalse(w.shouldRebuzz(50, t0 + 300_000L))
        assertTrue(w.shouldRebuzz(50, t0 + 361_000L + 60_000L))
        // Budget (2) spent → the next feed disarms; nothing ever fires again.
        assertFalse(w.shouldRebuzz(50, t0 + 500_000L))
        assertFalse(w.isArmed)
    }

    @Test fun watchWindowExpires() {
        val w = watcher()
        w.arm(t0, 48)
        assertFalse(w.shouldRebuzz(50, t0 + 180_000L))
        // First feed past the 10-min window disarms — a dip an hour after the alarm is not our business.
        assertFalse(w.shouldRebuzz(50, t0 + 601_000L))
        assertFalse(w.isArmed)
        assertFalse(w.shouldRebuzz(50, t0 + 630_000L))
    }

    @Test fun rearmRestartsWindowAndBudget() {
        val w = watcher()
        w.arm(t0, 48)
        assertFalse(w.shouldRebuzz(50, t0 + 180_000L))
        assertTrue(w.shouldRebuzz(50, t0 + 241_000L))
        // A NEW alarm fire re-arms: fresh window, fresh budget, spacing anchored to the new fire.
        val t1 = t0 + 86_400_000L   // next morning
        w.arm(t1, 52)
        assertTrue(w.isArmed)
        assertFalse(w.shouldRebuzz(53, t1 + 180_000L))
        assertTrue(w.shouldRebuzz(53, t1 + 241_000L))
    }

    @Test fun sleepWindowWatcherExposesItsTrough() {
        // The re-buzz arm path reads the light-sleep watcher's measured trough; pin the getter's
        // null-before-any-reading and lowest-reading-wins behaviour (ceiling-filtered like the
        // detection itself: a brief got-up spike must not become the floor).
        val sw = SleepWindowWatcher(riseBpm = 6, minSamples = 5, troughCeilingBpm = 90)
        assertNull(sw.nightTroughBpm)
        sw.shouldWake(120)                       // above ceiling — not a trough candidate
        assertNull(sw.nightTroughBpm)
        sw.shouldWake(55)
        sw.shouldWake(48)
        sw.shouldWake(60)
        assertEquals(48, sw.nightTroughBpm)
    }
}
