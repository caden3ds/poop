package com.noop.alarm

/**
 * Overnight HR-trough tracker for the fall-back-asleep re-buzz — PURE, fed by the BLE foreground
 * service on every live HR tick regardless of which alarm (if any) is armed.
 *
 * WHY: [RebuzzWatcher] judges "fell back asleep" against the night's sleeping HR floor. That floor
 * used to come from [SleepWindowWatcher], which is only fed INSIDE the phone alarm's wake window —
 * so a user waking to the strap's own scheduled firmware alarm (phone smart alarm off) never had a
 * measured floor and the re-buzz stood down every morning. This tracker measures the floor from the
 * whole overnight stream instead, so BOTH wake paths arm identically.
 *
 * Mechanism: readings are folded into fixed [bucketMs] buckets (each keeps its minimum), buckets
 * older than [windowMs] fall away, and the trough is the minimum across surviving buckets. Bounded
 * memory (≤ windowMs / bucketMs entries), O(1) feed.
 *
 * HONEST gates, same spirit as the watchers:
 *  - Readings above [ceilingBpm] are never trough candidates (a brief got-up spike, a workout).
 *  - [troughBpm] answers null until at least [minCoverageBuckets] buckets hold data — a floor
 *    measured from a few minutes of morning HR would just be "sitting still awake", and a re-buzz
 *    judged against THAT would nag an awake user. Requiring hours of coverage means the floor can
 *    only come from a genuinely slept-through, strap-streaming night; anything less and the re-buzz
 *    honestly stands down (the wake alarm itself already fired either way).
 */
class NightTroughTracker(
    /** Bucket width (ms). 5 min keeps the map tiny while still riding out brief dropouts. */
    private val bucketMs: Long = 5 * 60_000L,
    /** How far back a reading may sit and still shape tonight's floor. 8 h ≈ one night; yesterday's
     *  trough must never leak into tonight (the staleness bug a plain running-minimum would have). */
    private val windowMs: Long = 8 * 60 * 60_000L,
    /** Readings above this are never trough candidates. Mirrors [SleepWindowWatcher]'s ceiling. */
    private val ceilingBpm: Int = 90,
    /** Buckets with data required before the trough is trusted (36 × 5 min = 3 h of coverage). */
    private val minCoverageBuckets: Int = 36,
) {
    /** bucket index (nowMs / bucketMs) → minimum accepted bpm seen in that bucket. */
    private val buckets = HashMap<Long, Int>()

    /** Fold one live HR reading in. Non-positive (no data) and above-ceiling readings are ignored. */
    fun feed(bpm: Int, nowMs: Long) {
        if (bpm <= 0 || bpm > ceilingBpm) return
        prune(nowMs)
        val idx = nowMs / bucketMs
        val cur = buckets[idx]
        if (cur == null || bpm < cur) buckets[idx] = bpm
    }

    /**
     * The night's sleeping HR floor (bpm), or null when the window holds too little data to assert
     * one. Null means the caller must stand down, never guess.
     */
    fun troughBpm(nowMs: Long): Int? {
        prune(nowMs)
        if (buckets.size < minCoverageBuckets) return null
        return buckets.values.min()
    }

    /** Drop buckets that have aged out of the window. */
    private fun prune(nowMs: Long) {
        val cutoff = (nowMs - windowMs) / bucketMs
        buckets.keys.removeAll { it < cutoff }
    }
}
