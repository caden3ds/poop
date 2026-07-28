package com.noop.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the overnight HR-floor tracker the re-buzz arms against. The floor decides whether a
 * post-alarm HR reading means "fell back asleep", so the properties that matter are: never answer
 * from thin data, never let an awake spike become the floor, and never let yesterday's night leak
 * into tonight.
 */
class NightTroughTrackerTest {

    // 1-min buckets, 60-min window, 5 buckets of coverage required — small numbers, same shape.
    private fun tracker() = NightTroughTracker(
        bucketMs = 60_000L,
        windowMs = 60 * 60_000L,
        ceilingBpm = 90,
        minCoverageBuckets = 5,
    )

    private val t0 = 10_000_000L

    @Test fun answersNullBeforeEnoughCoverage() {
        val tr = tracker()
        // 4 buckets of data < the 5 required → no floor, the re-buzz must stand down.
        for (i in 0 until 4) tr.feed(50, t0 + i * 60_000L)
        assertNull(tr.troughBpm(t0 + 4 * 60_000L))
        // The fifth bucket crosses the coverage bar.
        tr.feed(52, t0 + 4 * 60_000L)
        assertEquals(50, tr.troughBpm(t0 + 4 * 60_000L))
    }

    @Test fun troughIsTheMinimumAcrossBuckets() {
        val tr = tracker()
        val readings = listOf(60, 55, 48, 58, 62)
        for ((i, bpm) in readings.withIndex()) tr.feed(bpm, t0 + i * 60_000L)
        assertEquals(48, tr.troughBpm(t0 + 5 * 60_000L))
    }

    @Test fun awakeSpikesNeverBecomeTheFloor() {
        val tr = tracker()
        // Readings above the 90 bpm ceiling (up in the night, a workout) are not candidates —
        // and also must not count toward coverage.
        for (i in 0 until 5) tr.feed(120, t0 + i * 60_000L)
        assertNull(tr.troughBpm(t0 + 5 * 60_000L))
        for (i in 5 until 10) tr.feed(50 + i, t0 + i * 60_000L)
        assertEquals(55, tr.troughBpm(t0 + 10 * 60_000L))
    }

    @Test fun nonPositiveReadingsIgnored() {
        val tr = tracker()
        for (i in 0 until 5) tr.feed(0, t0 + i * 60_000L)
        assertNull(tr.troughBpm(t0 + 5 * 60_000L))
    }

    @Test fun lastNightFallsOutOfTheWindow() {
        val tr = tracker()
        // A full "night" of low readings...
        for (i in 0 until 10) tr.feed(45, t0 + i * 60_000L)
        assertEquals(45, tr.troughBpm(t0 + 10 * 60_000L))
        // ...then query a window-length later: the old floor must be gone, not stale-served.
        val muchLater = t0 + 10 * 60_000L + 61 * 60_000L
        assertNull(tr.troughBpm(muchLater))
        // Fresh readings tonight rebuild a floor from tonight's data only.
        for (i in 0 until 5) tr.feed(58, muchLater + i * 60_000L)
        assertEquals(58, tr.troughBpm(muchLater + 5 * 60_000L))
    }

    @Test fun multipleReadingsInOneBucketKeepTheMinimum() {
        val tr = tracker()
        // Several readings landing in the SAME 1-min bucket: the bucket keeps its min, and one
        // bucket is one unit of coverage no matter how many samples hit it.
        for (i in 0 until 30) tr.feed(60 - i % 3, t0 + i * 1_000L)   // all within bucket 0
        assertNull(tr.troughBpm(t0 + 30_000L))                        // 1 bucket < 5 coverage
        for (i in 1 until 5) tr.feed(59, t0 + i * 60_000L)
        assertEquals(58, tr.troughBpm(t0 + 5 * 60_000L))              // bucket 0's min survives
    }
}
