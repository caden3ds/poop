package com.noop.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noop.ui.LucidPrefs
import java.util.Calendar
import kotlin.random.Random

/**
 * The DAYTIME half of lucid-dream training: reality-check cues.
 *
 * Fires the SAME triple micro-pulse the night half uses, at random times inside the user's chosen
 * waking window. The point is conditioning — you feel the pattern, you perform a physical reality
 * check, and after enough repetitions the pattern itself starts to prompt the check. When the same
 * pattern then arrives during REM, the trained response has somewhere to go.
 *
 * That is also why the pattern must never be reused for anything else: if a notification or an alarm
 * could feel like this, the conditioning attaches to the wrong stimulus and the whole thing is worse
 * than useless. See `WhoopBleClient.buzzLucidCue`.
 *
 * Deliberately INEXACT alarms, following [WindDownScheduler]: a reality check that slips ten minutes
 * is exactly as useful, so this needs no exact-alarm permission. Randomness is the feature — a
 * predictable cue stops prompting a genuine check and becomes background noise.
 *
 * The cue reaches the strap through the running connection service; with no strap connected the cue
 * is simply skipped (a phone-side notification would defeat the point — the conditioning is to a
 * WRIST sensation, not to a screen).
 */
object LucidRealityCheckScheduler {

    const val ACTION_REALITY_CHECK = "com.noop.alarm.action.LUCID_REALITY_CHECK"
    private const val REQUEST_CODE_BASE = 7420

    /** Hard cap on scheduled cues per day, independent of what the prefs say. */
    private const val MAX_PER_DAY = 12

    /** Minimum gap between reality checks (minutes) so randomness can't cluster two together. */
    const val MIN_GAP_MIN = 45

    /**
     * Is [minuteOfDay] inside the user's waking window? Checked again AT FIRE TIME, not just when
     * scheduling.
     *
     * These are deliberately non-wakeup alarms, so Android defers one that comes due while the phone is
     * dozing until the device next wakes — which can be hours later and well outside the window. A
     * reality check that lands at midnight is worse than one that never fires: it trains the cue against
     * being asleep, which is the exact opposite of the intent, and it buzzes someone in bed.
     */
    internal fun withinWindow(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean =
        minuteOfDay >= startMin && minuteOfDay < endMin

    /**
     * (Re)schedule today's reality checks. Cancels any existing ones first so a settings change
     * doesn't stack two sets. No-op when the daytime half is off.
     */
    fun schedule(context: Context, now: Calendar = Calendar.getInstance()) {
        cancel(context)
        if (!LucidPrefs.dayEnabled(context)) return

        val count = LucidPrefs.dayCuesPerDay(context).coerceIn(1, MAX_PER_DAY)
        val startMin = LucidPrefs.dayStartMin(context)
        val endMin = LucidPrefs.dayEndMin(context)
        val minutes = pickTimes(startMin, endMin, count, Random.Default)

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nowMinuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        minutes.forEachIndexed { i, minuteOfDay ->
            val at = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
                set(Calendar.MINUTE, minuteOfDay % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // A slot already past today rolls to TOMORROW rather than being dropped.
                //
                // Dropping it looked harmless and was not: the ONLY thing that re-arms this schedule is
                // the receiver, which runs only when a cue actually fires. So a day whose remaining slots
                // were all in the past armed nothing, nothing ever fired, and the feature went silently
                // dead until the toggle was flipped again. That also hit the normal path — after the LAST
                // cue of the day, the re-arm picked fresh slots that were all in the past and armed
                // nothing. Rolling forward keeps a full set of alarms live across the next 24h, so the
                // schedule always carries itself into the following day.
                if (minuteOfDay <= nowMinuteOfDay) add(Calendar.DAY_OF_MONTH, 1)
            }
            // Inexact and non-wakeup: a reality check must never punch through Doze to buzz someone.
            am.set(AlarmManager.RTC, at.timeInMillis, pendingIntent(context, i))
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until MAX_PER_DAY) am.cancel(pendingIntent(context, i))
    }

    /**
     * Choose [count] random minute-of-day slots inside [startMin, endMin), spaced at least
     * [MIN_GAP_MIN] apart.
     *
     * Pure and seedable so the spacing guarantee is unit-testable. When the window is too narrow to
     * hold [count] properly-spaced slots it returns FEWER rather than bunching them up — a cluster of
     * cues trains nothing and is just an annoyance.
     */
    internal fun pickTimes(startMin: Int, endMin: Int, count: Int, random: Random): List<Int> {
        val lo = startMin.coerceIn(0, 24 * 60)
        val hi = endMin.coerceIn(0, 24 * 60)
        if (hi - lo < MIN_GAP_MIN || count <= 0) return emptyList()

        val chosen = ArrayList<Int>(count)
        // Bounded attempts: rejection sampling, but it must terminate on a narrow window.
        var attempts = 0
        while (chosen.size < count && attempts < count * 40) {
            attempts++
            val candidate = lo + random.nextInt(hi - lo)
            if (chosen.none { kotlin.math.abs(it - candidate) < MIN_GAP_MIN }) chosen.add(candidate)
        }
        return chosen.sorted()
    }

    private fun pendingIntent(context: Context, index: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + index,
            Intent(context, LucidRealityCheckReceiver::class.java).setAction(ACTION_REALITY_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/**
 * Fires one reality-check cue on the strap.
 *
 * Sends the buzz only; there is deliberately NO notification fallback when the strap is away. The
 * conditioning is to a wrist sensation, and a phone banner would train a different cue.
 */
class LucidRealityCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LucidRealityCheckScheduler.ACTION_REALITY_CHECK) return
        if (!LucidPrefs.dayEnabled(context)) return
        // A deferred alarm can arrive long after its slot (see withinWindow). Drop the buzz when it does
        // — but STILL fall through to the re-arm below, or a single late delivery would end the schedule.
        val now = Calendar.getInstance()
        val nowMinuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val inWindow = LucidRealityCheckScheduler.withinWindow(
            nowMinuteOfDay,
            LucidPrefs.dayStartMin(context),
            LucidPrefs.dayEndMin(context),
        )
        if (inWindow) runCatching {
            val app = context.applicationContext as com.noop.NoopApplication
            app.ble.buzzLucidCue(bursts = 1)
            val prefs = LucidPrefs.of(context)
            val today = java.time.LocalDate.now().toString()
            val fired = if (prefs.getString(LucidPrefs.DAY_KEY, null) == today) {
                prefs.getInt(LucidPrefs.DAY_CUES_FIRED, 0)
            } else {
                0
            }
            prefs.edit()
                .putString(LucidPrefs.DAY_KEY, today)
                .putInt(LucidPrefs.DAY_CUES_FIRED, fired + 1)
                .apply()
        }
        // Re-arm for the following day, so the schedule keeps rolling without the app being opened.
        runCatching { LucidRealityCheckScheduler.schedule(context) }
    }
}
