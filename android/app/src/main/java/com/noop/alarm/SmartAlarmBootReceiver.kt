package com.noop.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the guaranteed wake alarm after a device reboot (#207).
 *
 * AlarmManager schedules are cleared by a restart, so without this a phone that reboots overnight
 * would silently drop the alarm — exactly the failure the safety guarantee exists to prevent. On
 * BOOT_COMPLETED (and the OEM "quick boot" variant) we re-schedule the SAME persisted hard deadline
 * via [SmartAlarmScheduler.rearmPersisted], which no-ops if the alarm is disabled or already past.
 */
class SmartAlarmBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                runCatching {
                    SmartAlarmScheduler.rearmPersisted(context, SmartAlarmStore.from(context))
                }
                // Bring the connection service back after a reboot. Without this nothing background
                // ran until the app was next opened by hand — no sleep detection, no smart alarm
                // watch, no lucid cue — and the user's only clue was a missing notification.
                runCatching {
                    if (com.noop.ui.NoopPrefs.backgroundConnection(context)) {
                        com.noop.ble.WhoopConnectionService.start(context)
                    }
                }
                // Re-arm the daytime lucid reality checks: inexact alarms do not survive a reboot.
                runCatching { LucidRealityCheckScheduler.schedule(context) }
                // Re-schedule the (non-critical) wind-down nudge too — inexact repeating alarms are
                // cleared by a reboot on many OEMs, so re-arm from the user's earliest wake time.
                runCatching {
                    val wind = WindDownStore.from(context)
                    if (wind.enabled) {
                        val wake = SmartAlarmStore.from(context).targetMinutes
                        WindDownScheduler.schedule(context, wind, wake)
                    }
                }
            }
        }
    }
}
