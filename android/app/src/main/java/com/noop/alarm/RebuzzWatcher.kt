package com.noop.alarm

/**
 * The fall-back-asleep detector for the smart alarm — PURE, the [SleepWindowWatcher]'s inverse twin.
 *
 * After a wake alarm fires (either the phone's guaranteed alarm or the strap's own firmware alarm),
 * this watches the live heart rate for a bounded window and decides whether the user has drifted BACK
 * to sleep — so the caller can buzz the wrist again. Where [SleepWindowWatcher] fires on HR RISING
 * away from the nightly trough (lighter phase → wake now), this fires on HR SINKING back down to that
 * trough and STAYING there (you're not up, you're re-asleep → buzz again).
 *
 * It never touches BLE or AlarmManager itself; it only DECIDES. The caller (the BLE foreground
 * service) arms it with the night's trough when an alarm-fired stamp appears, feeds it the live HR,
 * and buzzes the strap when [shouldRebuzz] returns true.
 *
 * HONEST signal, no over-claiming — the same spirit as the light-sleep watcher:
 *  - The sustain requirement is TIME-based, not sample-count-based, so it is robust to the BLE
 *    stream's variable cadence: HR must sit near the trough CONTINUOUSLY for [sustainMs] before a
 *    re-buzz. A single elevated reading (you rolled over, you're scrolling) resets the run.
 *  - A non-positive HR (no live data) also resets the run — with no signal we cannot vouch that the
 *    user is asleep, and with BLE down the buzz couldn't reach the strap anyway.
 *  - Everything is bounded: the watch expires [watchWindowMs] after the alarm, at most [maxRebuzzes]
 *    re-buzzes fire, and consecutive re-buzzes are at least [minSpacingMs] apart (spacing is also
 *    enforced from the ORIGINAL alarm, so a re-buzz can never land on the alarm's heels).
 *  - Arming requires a real measured trough. If the night produced none (strap not streaming inside
 *    the wake window), the watcher simply never arms and the feature is an honest no-op.
 */
class RebuzzWatcher(
    /** How far above the nightly trough (bpm) still counts as "back at the sleep floor". Deliberately
     *  narrower than [SleepWindowWatcher.riseBpm] (6) so "re-asleep" and "lighter phase" can't both be
     *  true for the same reading. */
    private val nearTroughBpm: Int = 4,
    /** How long (ms) HR must sit near the trough CONTINUOUSLY before a re-buzz. ~3 min filters the
     *  brief post-snooze dip of someone lying still but awake. */
    private val sustainMs: Long = 3 * 60_000L,
    /** How long (ms) after the alarm fired the watcher keeps watching. Past this you're either up or
     *  have decisively chosen to sleep on — repeated buzzing an hour later would be noise. */
    private val watchWindowMs: Long = 30 * 60_000L,
    /** Ceiling on re-buzzes per alarm. */
    private val maxRebuzzes: Int = 3,
    /** Minimum spacing (ms) between buzzes (measured from the alarm itself for the first one). */
    private val minSpacingMs: Long = 4 * 60_000L,
) {
    private companion object {
        const val NO_RUN = -1L
        const val NOT_ARMED = -1L
    }

    private var armedAtMs: Long = NOT_ARMED
    private var troughBpm: Int = Int.MAX_VALUE
    /** Start of the current CONTINUOUS near-trough run, or [NO_RUN] when the last reading broke it. */
    private var runStartMs: Long = NO_RUN
    private var rebuzzCount: Int = 0
    private var lastBuzzMs: Long = 0L

    /** True while armed and inside the watch window (assuming feeds keep arriving). */
    val isArmed: Boolean get() = armedAtMs != NOT_ARMED

    /**
     * Arm for one just-fired alarm. [troughBpm] is the night's lowest smoothed HR
     * ([SleepWindowWatcher.nightTroughBpm]) — the floor "re-asleep" is judged against. An invalid
     * trough (≤ 0 or unmeasured) leaves the watcher DISARMED: without a floor we will not guess.
     * Re-arming (a second alarm stamp) restarts the window and the budget.
     */
    fun arm(nowMs: Long, troughBpm: Int) {
        if (troughBpm <= 0 || troughBpm == Int.MAX_VALUE) {
            disarm()
            return
        }
        armedAtMs = nowMs
        this.troughBpm = troughBpm
        runStartMs = NO_RUN
        rebuzzCount = 0
        // Space the FIRST re-buzz from the alarm itself, so it can never fire on the alarm's heels.
        lastBuzzMs = nowMs
    }

    /** Stand down (watch expired, budget spent, feature toggled off, or a fresh [arm] replacing us). */
    fun disarm() {
        armedAtMs = NOT_ARMED
        troughBpm = Int.MAX_VALUE
        runStartMs = NO_RUN
        rebuzzCount = 0
        lastBuzzMs = 0L
    }

    /**
     * Feed one smoothed HR reading. Returns true when the user has demonstrably fallen back asleep
     * (HR continuously near the trough for [sustainMs]) and a re-buzz is due — the caller buzzes the
     * strap on true. After firing, the sustained run resets, so the NEXT re-buzz needs a fresh
     * continuous dip (someone the buzz actually woke shows a rise that keeps the run broken).
     */
    fun shouldRebuzz(bpm: Int, nowMs: Long): Boolean {
        if (armedAtMs == NOT_ARMED) return false
        if (nowMs - armedAtMs > watchWindowMs || rebuzzCount >= maxRebuzzes) {
            disarm()
            return false
        }
        // No live HR → cannot vouch the user is asleep; break the run rather than coast on silence.
        if (bpm <= 0) {
            runStartMs = NO_RUN
            return false
        }
        if (bpm > troughBpm + nearTroughBpm) {
            runStartMs = NO_RUN   // off the sleep floor — awake enough
            return false
        }
        if (runStartMs == NO_RUN) runStartMs = nowMs
        if (nowMs - runStartMs < sustainMs) return false
        if (nowMs - lastBuzzMs < minSpacingMs) return false
        runStartMs = NO_RUN
        lastBuzzMs = nowMs
        rebuzzCount += 1
        return true
    }
}
