package com.noop.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.noop.NoopApplication
import com.noop.R
import com.noop.alarm.LucidNightRunner
import com.noop.alarm.NightTroughTracker
import com.noop.alarm.RebuzzWatcher
import com.noop.alarm.SleepWindowWatcher
import com.noop.alarm.SmartAlarmScheduler
import com.noop.alarm.SmartAlarmStore
import com.noop.analytics.LiveRemEstimator
import com.noop.analytics.LucidCuePolicy
import com.noop.ui.LucidPrefs
import com.noop.analytics.BatteryEstimator
import com.noop.analytics.IllnessWatch
import com.noop.analytics.RestScorer
import com.noop.data.DailyMetric
import com.noop.location.GpsSession
import com.noop.location.LocationTracker
import com.noop.notif.BatteryAlertNotifier
import com.noop.notif.IllnessAlertNotifier
import com.noop.ui.NoopPrefs
import com.noop.ui.appLaunchIntent
import com.noop.widget.WidgetSnapshot
import com.noop.widget.WidgetSnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service that keeps the WHOOP BLE connection alive while the app is backgrounded or
 * closed.
 *
 * Android tears a process down shortly after its last Activity goes away, which is exactly why
 * people on Reddit saw the strap disconnect the moment they closed NOOP. A started foreground
 * service — with an ongoing notification — keeps the process (and therefore the
 * [com.noop.NoopApplication]-owned [WhoopBleClient] and its GATT link) resident, so heart rate
 * keeps streaming and offloads keep landing in the background.
 *
 * It does **not** own or drive the connection: it simply holds the process up and mirrors the
 * client's [LiveState] into the notification. Start/stop is gated by a Settings toggle (see
 * `NoopPrefs.backgroundConnection`) and only ever happens from the foreground (on connect / when
 * the user flips the toggle), so we never trip Android 12+'s background-start restriction.
 *
 * The matching capability on macOS is free: `AppModel` is an app-level `@StateObject` kept alive by
 * the menu-bar extra, so closing the window leaves the strap connected.
 */
/**
 * One tick of the ongoing-notification/widget stream. Carries TWO day rows on purpose (#911):
 * [todayRow] is the naive/unscored today row the notification's Recovery line reads (honest-null until
 * tonight is scored), while [anchorRow] is the widget-only carried anchor (today when scored, else the
 * freshest prior scored day) so the widget describes the same day as Today without the notification ever
 * showing a carried figure as if it were live.
 */
private data class NotifyTick(
    val state: LiveState,
    val todayRow: DailyMetric?,
    val anchorRow: DailyMetric?,
    val illness: String?,
)

class WhoopConnectionService : Service() {

    /** Main-thread scope used only to mirror [LiveState] into the notification. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The single live-state→notification collector. Re-`start`s land here repeatedly (on every
     *  connect, plus any OS restart), so we cancel the old one before launching a new one. */
    private var notifyJob: Job? = null

    /** Watches [GpsSession] and runs the platform location stream while a GPS workout is active. This
     *  is what makes route tracking survive the screen turning off (#215): the collection lives on the
     *  always-on service, not the Activity-scoped ViewModel that Android cancels when it's cleared. */
    private var gpsGateJob: Job? = null

    /** The actual location collector, alive only while a GPS workout is in flight. Cancelled (which
     *  removes the LocationManager updates via the stream's awaitClose) the moment the workout ends. */
    private var gpsJob: Job? = null

    /** Platform-GPS wrapper (no Google Play Services). Lazily built — the service holds a Context. */
    private val locationTracker by lazy { LocationTracker(this) }

    /** Last illness-watch evaluation seen by the collector — clear→raised is the notify edge.
     *  In-memory on purpose: the persisted once-a-day gate (NoopPrefs) handles dedupe across
     *  process restarts and the AppViewModel call site. */
    private var lastIllnessAlert: String? = null

    /** Last battery % the predictive runtime alert was evaluated at. The live-state flow emits far
     *  more often than the strap's ~8-min battery cadence; gating the Room read + estimator fit on an
     *  actual SoC change keeps the predictive path as cheap as the SoC-only alert beside it. */
    private var lastRuntimeEvalPct: Int? = null

    /** Smart-alarm light-sleep watcher (#207). Feeds the live HR while we're inside the wake window
     *  and, on a lighter-phase reading, advances the GUARANTEED alarm earlier. It can only ever move
     *  the alarm earlier within the window — the hard deadline scheduled via AlarmManager is the floor
     *  of safety, so if BLE drops or no light sleep is found the user is still woken at the window end.
     *  The detector is reset each time we (re)enter a window. */
    private val sleepWatcher = SleepWindowWatcher()
    private var inAlarmWindow = false

    /** Fall-back-asleep re-buzz watcher. Armed when a fresh alarm-fired stamp appears in the store
     *  (either wake path writes one) with the night trough measured by [nightTrough]; fed the same
     *  live HR; on a sustained sink back to the sleep floor the strap is woken again (one-shot
     *  firmware alarm + immediate buzz). Bounded and opt-in — see [RebuzzWatcher]. */
    private val rebuzzWatcher = RebuzzWatcher()

    /** Overnight HR-floor tracker for the re-buzz — fed EVERY live HR tick, independent of any alarm
     *  state, so the floor exists for the strap's own scheduled firmware alarm too (the light-sleep
     *  watcher above only ever learns it inside the PHONE alarm's window). Windowed to one night so a
     *  previous night's floor can never leak into today's re-buzz judgement. */
    private val nightTrough = NightTroughTracker()

    /** The alarm-fired stamp the re-buzz watcher was last armed off, so one fire arms exactly once
     *  (the collector sees the same persisted stamp on every HR tick). */
    private var rebuzzArmedForFireMs = 0L

    /** Lucid-dream REM cue runtime. Fed the SAME live HR tick as the re-buzz + trough tracker; all of
     *  its restraint lives in the pure [com.noop.analytics.LucidCuePolicy]. Opt-in, default off. */
    private val lucidRunner = LucidNightRunner()

    /** The learned personal REM template, loaded once per night from scored history. Null means the
     *  estimator stands down (cold start) — the runner is fed it either way and refuses on its own. */
    private var lucidTemplate: LiveRemEstimator.RemTemplate? = null

    /** The local date the lucid night counters + template belong to; a new date resets both. */
    private var lucidNightKey: String? = null

    /** First HR tick of this sleep stretch at or below the sleep ceiling — the approximate sleep onset
     *  the REM cycle prior is measured from. APPROXIMATE on purpose: it is the same "under the ceiling"
     *  heuristic [SleepWindowWatcher] already encodes, and the prior only needs a coarse position in
     *  the night. Cleared whenever HR sits above the ceiling long enough to mean the user is up. */
    private var lucidAsleepSinceMs: Long? = null

    /** Consecutive above-ceiling ticks, used to clear [lucidAsleepSinceMs] without a single stray
     *  high reading (a turn-over spike) resetting the whole night's clock. */
    private var lucidAwakeTicks = 0

    /** The smart-alarm HR collector, alive for the life of the service. */
    private var alarmJob: Job? = null

    private val ble get() = (application as NoopApplication).ble
    private val repo get() = (application as NoopApplication).repository

    /**
     * Watches the OS Bluetooth radio so turning it off immediately tears down NOOP's orphaned GATT
     * link (#314). Without this there is no ACTION_STATE_CHANGED listener at all, so the radio going off
     * never reaches [WhoopBleClient] — the link stays "connected", the UI keeps showing live HR/buzz/sync
     * that isn't real, and the next write crashes on a dead binder (iOS/macOS are immune because
     * CoreBluetooth's send() is state-guarded). Registered while the FGS is alive (it is the long-lived
     * owner of the connection) and unregistered in [onDestroy]. STATE_TURNING_OFF/OFF → teardown +
     * connected=false; STATE_ON → resume the connection.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                // Catch TURNING_OFF (the earliest signal) AND OFF — by TURNING_OFF the binder is already
                // on its way down, so tearing down here pre-empts the crash window.
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> ble.onBluetoothRadioOff()
                BluetoothAdapter.STATE_ON -> ble.onBluetoothRadioOn()
            }
        }
    }

    /** True once [bluetoothStateReceiver] is registered, so repeat onStartCommands don't double-register
     *  (which would later throw on a single unregister). */
    private var bluetoothReceiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification "Disconnect" action routes back here as a self-intent.
        if (intent?.action == ACTION_STOP) {
            runCatching { ble.disconnect() }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel()
        // Must call startForeground promptly after startForegroundService(). If it fails (e.g. the
        // API 34 connectedDevice type needs BLUETOOTH_CONNECT and the user denied it) we stop cleanly
        // rather than crash — the connection itself keeps working in the foreground regardless.
        if (!startForegroundCompat(buildNotification(ble.state.value, null))) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Listen for the OS Bluetooth radio toggling so turning it off tears the link down at once (#314).
        // Guarded so repeat onStartCommands (every connect / OS restart) don't stack registrations.
        if (!bluetoothReceiverRegistered) {
            runCatching {
                ContextCompat.registerReceiver(
                    this,
                    bluetoothStateReceiver,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.onSuccess { bluetoothReceiverRegistered = true }
        }

        // Keep the ongoing notification in step with the live connection state AND today's recovery
        // (the 15-min IntelligenceEngine recompute), so it re-posts when either changes — a glanceable
        // poor-man's Live Activity (#42). daysMergedFlow is the same merged store the dashboard reads.
        notifyJob?.cancel()
        notifyJob = scope.launch {
            combine(
                ble.state,
                // Defence-in-depth: a Room/disk error in this flow would otherwise propagate uncaught
                // out of scope.launch and kill the process — the FGS exists to protect the connection,
                // not to take it down. (Audited during #82, which proved unrelated/unreproducible —
                // this guard is belt-and-braces, not a diagnosed fix.) After catch{emit} the inner
                // flow completes; combine keeps running on ble.state with days frozen.
                // #797: the bounded merge (recentDaysMergedFlow) is enough here, the notification only reads
                // today's row; this stops a years-deep import re-merging the whole history on every change.
                repo.recentDaysMergedFlow("my-whoop").catch { emit(emptyList()) },
            ) { state, days ->
                // #911: resolve the day the way the dashboard does, via the LOGICAL local day (rolls at
                // 04:00, with the #304 pre-04:00 carve-out), NOT a naive LocalDate.now() that rolls at
                // midnight and starts looking up a brand-new, not-yet-scored calendar day. Two DISTINCT
                // rows come out, so the two surfaces keep their own honest contracts:
                val logicalKey = com.noop.ui.logicalDayKeyNow()
                val localKey = java.time.LocalDate.now().toString()
                //  - todayRow: the naive/unscored today row. The ongoing notification's Recovery line must
                //    stay on THIS (honest-null until tonight is scored), never on a carried prior-day
                //    figure, or the lock-screen would silently show yesterday's Recovery% as if it were
                //    live, with no provenance caption.
                //  - anchorRow: today's row when scored, else the freshest STRICTLY-PRIOR scored day carried
                //    over (via the SHARED `widgetAnchorRow`, mirroring TodayScreen + the #547 future-day
                //    guard). ONLY the widget uses this, so the 2x2 widget shows the same day as Today rather
                //    than blanking in the small hours before tonight is scored. This keeps the service
                //    symmetric with AppViewModel, where only the widget push reads the anchor.
                val todayRow = com.noop.ui.resolveTodayRow(days, logicalKey, localKey)
                val anchorRow = com.noop.ui.widgetAnchorRow(days, logicalKey, localKey)
                NotifyTick(
                    state = state,
                    todayRow = todayRow,
                    anchorRow = anchorRow,
                    // Illness watch in the background (gated on the opt-out pref): the FGS is the
                    // only long-lived collector, so this is what makes the early-warning reach a
                    // user who hasn't opened the app today.
                    illness = if (NoopPrefs.illnessWatch(this@WhoopConnectionService)) IllnessWatch.evaluate(days) else null,
                )
            }.catch { /* belt-and-braces: a frozen notification beats a dead process */ }
                // conflate + collect, NOT collectLatest (#82): the widget push suspends in Glance
                // machinery longer than the live-HR emission interval, so collectLatest cancelled
                // every push mid-flight and the widget starved on stale data the moment HR started
                // streaming. Conflation still processes only the latest value — just without the axe.
                .conflate()
                .collect { (state, todayRow, anchorRow, illness) ->
                // Honest-null: the notification's Recovery line reads the NAIVE today row, never the
                // carried anchor, so it stays blank until tonight's recovery actually lands (#911).
                postNotification(state, todayRow?.recovery)
                // Banner transition (clear → raised) → real system notification; the notifier's
                // persisted day gate dedupes against the app-open (AppViewModel) call site.
                if (lastIllnessAlert == null && illness != null) {
                    IllnessAlertNotifier.onEvaluated(this@WhoopConnectionService, illness)
                }
                lastIllnessAlert = illness
                // Battery alerts — low (≤15%) and charge-complete (100%). The once-per-crossing
                // dedupe is persisted in NoopPrefs (BatteryAlertPolicy), so no in-memory pct tracking.
                BatteryAlertNotifier.onBatteryUpdate(
                    this@WhoopConnectionService,
                    currPct = state.batteryPct?.roundToInt(),
                    charging = state.charging,
                )
                // Predictive runtime alert (iOS/macOS twin: BatteryNotifier.onRuntimeEstimate):
                // re-fit the "~X left" estimate from the persisted SoC series and warn at ≤24 h of
                // runtime, whatever the strap generation. Evaluated only when the battery % actually
                // changes (~8-min strap cadence), so the Room read + slope fit never rides every
                // live-state emission. Same samples/rated inputs as the Today badge, so the alert can
                // never disagree with the number on screen.
                val runtimePct = state.batteryPct?.roundToInt()
                if (runtimePct != null && runtimePct != lastRuntimeEvalPct) {
                    lastRuntimeEvalPct = runtimePct
                    runCatching {
                        val nowS = System.currentTimeMillis() / 1000
                        val samples = repo.batterySamples("my-whoop", nowS - 14L * 86_400, nowS, limit = 2_000)
                            .mapNotNull { s -> s.soc?.let { s.ts to it } }
                        val rated = if (state.whoop5Detected) BatteryEstimator.ratedLifeHoursWhoop5
                                    else BatteryEstimator.ratedLifeHoursWhoop4
                        BatteryAlertNotifier.onRuntimeEstimate(
                            this@WhoopConnectionService,
                            remainingHours = BatteryEstimator.estimate(samples, rated)?.hoursRemaining,
                            charging = state.charging,
                        )
                    }
                }
                // Feed the home-screen widget from the same stream — this service is its heartbeat
                // while the app UI is closed. Throttled + no-op without a placed widget (the store
                // checks both); runCatching so a Glance hiccup never tears down the connection.
                runCatching {
                    WidgetSnapshotStore.push(
                        this@WhoopConnectionService,
                        WidgetSnapshot(
                            recoveryPct = anchorRow?.recovery?.roundToInt(),
                            // Rest = the sleep_performance composite from the anchor row's banked stage
                            // figures (pure, honest-null until last night is scored); Effort = the 0-100
                            // strain. Widget-only carry, so it shows the same day as Today. (#516/#911)
                            restPct = anchorRow?.let { RestScorer.restFromDaily(it)?.roundToInt() },
                            effortPct = anchorRow?.strain?.roundToInt(),
                            heartRate = state.heartRate,
                            batteryPct = state.batteryPct?.roundToInt(),
                            connected = state.connected,
                            updatedAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }

        // Drive GPS route tracking from here so it OUTLIVES the UI (#215). While a GPS workout is
        // active we collect the platform location stream into the process-level [GpsSession]; the
        // ViewModel only observes that shared route. Gated on the active flag so the location radio is
        // off (and the FGS's location type unused) outside a GPS workout. Re-`start`s land here, so we
        // cancel + relaunch the gate, never stack collectors.
        gpsGateJob?.cancel()
        gpsGateJob = scope.launch {
            GpsSession.state
                .map { it.active }
                .distinctUntilChanged()
                .collect { active ->
                    gpsJob?.cancel()
                    gpsJob = null
                    if (active) {
                        // Re-post with the location service type added so background location is
                        // permitted while tracking; on Android 14+ a service that reads location in the
                        // background must declare the location FGS type. Reverted to connectedDevice-only
                        // when the workout ends (active=false re-posts the base type).
                        startForegroundCompat(buildNotification(ble.state.value, null), tracking = true)
                        // Workouts & GPS test mode (Test Centre): wire the GpsSession fix-progress sink to the
                        // .workouts-tagged strap log ONLY when the WORKOUTS mode is on (one SharedPreferences
                        // bool read here). When off, the sink stays null and the route fold is byte-identical.
                        GpsSession.workoutsLog =
                            if (com.noop.testcentre.TestCentre.from(applicationContext)
                                    .active(com.noop.testcentre.TestDomain.WORKOUTS)
                            ) {
                                { line -> ble.externalLog(line, com.noop.testcentre.TestDomain.WORKOUTS) }
                            } else {
                                null
                            }
                        gpsJob = launch {
                            // LocationTracker fails SAFE (no permission / no provider just ends the
                            // stream); runCatching guards an OEM throw so it can't tear down the FGS.
                            runCatching {
                                locationTracker.stream().collect { pt -> GpsSession.append(pt) }
                            }
                        }
                    } else {
                        GpsSession.workoutsLog = null   // route finished: drop the test-mode sink
                        startForegroundCompat(buildNotification(ble.state.value, null), tracking = false)
                    }
                }
        }

        // Smart alarm light-sleep watcher (#207). While the alarm is enabled and we're inside the wake
        // window, feed each live HR reading to the pure detector; on a lighter-phase reading, advance
        // the GUARANTEED alarm earlier (the scheduler clamps to the window and can never move it later
        // or cancel it — the hard deadline set via AlarmManager is independent of this collector). The
        // FGS is the only long-lived BLE collector, so this is what lets the smart move happen with the
        // app closed. If the service isn't running (user opted out of background) the hard deadline
        // still fires — that's the point of the fallback.
        alarmJob?.cancel()
        alarmJob = scope.launch {
            val store = SmartAlarmStore.from(this@WhoopConnectionService)
            ble.state
                .map { it.heartRate ?: 0 }
                .conflate()
                .collect { hr ->
                    val now = System.currentTimeMillis()
                    // Track the overnight HR floor UNCONDITIONALLY (cheap, bounded) so the re-buzz has
                    // a measured floor no matter which alarm wakes the user — phone wake window OR the
                    // strap's own scheduled firmware alarm. The tracker self-gates (ceiling, one-night
                    // window, hours-of-coverage minimum before it answers).
                    nightTrough.feed(hr, now)
                    // Lucid-dream REM cue. Rides the SAME tick as the trough tracker so it needs no
                    // second collector or wake-lock; entirely opt-in and silent by default. Every
                    // refusal (cold start, no floor, budget, arousal) is decided inside the pure
                    // policy/estimator — this block only supplies inputs and performs the buzz.
                    runCatching { tickLucid(hr, now) }
                    // Fall-back-asleep re-buzz. Runs BEFORE the enabled/deadline short-circuit below:
                    // by the time the post-fire HR arrives, the receiver has already cleared + re-armed
                    // the schedule for TOMORROW, so the window logic below is dormant — but the re-buzz
                    // watch is exactly then. Arm once per fresh fire stamp, seeded with tonight's
                    // measured HR floor; no measured floor → honest no-op.
                    if (store.rebuzzEnabled) {
                        val firedAt = store.lastFiredAtMs
                        if (firedAt > rebuzzArmedForFireMs && now - firedAt <= REBUZZ_STAMP_FRESH_MS) {
                            rebuzzArmedForFireMs = firedAt
                            val trough = nightTrough.troughBpm(now)
                            if (trough != null) {
                                rebuzzWatcher.arm(now, trough)
                                ble.externalLog("Re-buzz: armed (night floor=${trough}bpm) after alarm fire")
                            } else {
                                ble.externalLog("Re-buzz: too little overnight HR to know tonight's floor — standing down")
                            }
                        }
                        if (rebuzzWatcher.shouldRebuzz(hr, now)) {
                            // The persistent wake: arm the strap's OWN firmware alarm a minute out —
                            // unlike a notification buzz (a few motor loops, sleep-through-able), the
                            // firmware alarm keeps buzzing until the user double-taps it off. It is
                            // one-shot, so firing consumes it; the fire event (57) then re-stamps
                            // lastFiredAtMs, which re-arms THIS watcher for another round while the
                            // user stays at the sleep floor, and AppViewModel restores the normal
                            // schedule shortly after each execution. armStrapAlarm self-gates (5/MG
                            // needs Experimental and logs when not armed), so the immediate soft buzz
                            // below is both the heads-up and the fallback where it can't arm.
                            ble.buzz(3)
                            ble.armStrapAlarm(now / 1000L + REBUZZ_ALARM_LEAD_S)
                            ble.externalLog(
                                "Re-buzz: HR back at the sleep floor — buzzed, and armed a one-shot strap alarm ${REBUZZ_ALARM_LEAD_S}s out",
                            )
                        }
                    } else if (rebuzzWatcher.isArmed) {
                        rebuzzWatcher.disarm()
                    }
                    if (!store.enabled || store.scheduledDeadlineMs <= 0L) {
                        inAlarmWindow = false
                        return@collect
                    }
                    val inWindow = now in store.scheduledWindowStartMs until store.scheduledDeadlineMs
                    if (inWindow && !inAlarmWindow) sleepWatcher.reset()   // fresh night
                    inAlarmWindow = inWindow
                    if (!inWindow) return@collect
                    if (sleepWatcher.shouldWake(hr)) {
                        SmartAlarmScheduler.advanceTo(this@WhoopConnectionService, store, now)
                    }
                }
        }

        // START_NOT_STICKY: the FGS's job is to keep this process *alive* (which it does while
        // running, making OS kills unlikely). We deliberately do NOT resurrect after a kill, because
        // a fresh process has no strap/model context to reconnect with — the user reopening the app
        // re-establishes it. Resurrecting would only show a "Reconnecting…" notification that never
        // resolves.
        return START_NOT_STICKY
    }

    /** Promote to the foreground. Returns false (rather than throwing) if the platform refuses. When
     *  [tracking] a GPS workout we add the location FGS type — Android 14+ requires it for a service
     *  that reads location in the background (the manifest declares `connectedDevice|location`). */
    private fun startForegroundCompat(notification: Notification, tracking: Boolean = false): Boolean = runCatching {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val locationType = if (tracking) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or locationType
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }.isSuccess

    /** Signature of the fields the notification actually renders (#216). The live HR stream emits ~1 Hz
     *  but the notification no longer shows BPM, so we only re-post when one of THESE changes — turning
     *  a per-beat wakeup into a handful of updates a day. */
    private var lastNotificationKey: String? = null

    private fun postNotification(state: LiveState, recoveryPct: Double? = null) {
        val key = listOf(
            state.connected,
            state.backfilling,
            recoveryPct?.roundToInt(),
            state.batteryPct?.roundToInt(),
        ).joinToString("|")
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        // Defensive: a notify() throw (OEM quirk, revoked POST_NOTIFICATIONS on some ROMs) must not
        // crash the collector and tear down the connection we exist to keep alive.
        runCatching {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIF_ID, buildNotification(state, recoveryPct))
        }
    }

    private fun buildNotification(state: LiveState, recoveryPct: Double?): Notification {
        // #216: deliberately NO live BPM in the title. A per-beat-changing notification forces the
        // foreground service to re-post (and wake the device) ~once a second all day, which is a real
        // battery cost for a number nobody reads off the lock screen. The title now reflects only the
        // connection / sync state, which changes rarely — see postNotification's dedup.
        val title = when {
            !state.connected   -> "Reconnecting to your WHOOP…"
            state.backfilling  -> "Syncing strap history…"
            else               -> "Connected to your WHOOP"
        }
        val detail = buildList {
            add(if (state.connected) "Streaming in the background" else "Keeping the link open")
            recoveryPct?.let { add("Recovery ${it.roundToInt()}%") }
            state.batteryPct?.let { add("Strap ${it.roundToInt()}%") }
        }.joinToString("  ·  ")

        val openApp = PendingIntent.getActivity(
            this,
            0,
            appLaunchIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAction = PendingIntent.getService(
            this,
            1,
            Intent(this, WhoopConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openApp)
            .addAction(0, "Disconnect", stopAction)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Defensive: channel creation can throw on some OEM ROMs / under memory pressure; never let
        // that crash onStartCommand (it would take the FGS — and the connection — down with it).
        runCatching {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Strap connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while POOP keeps your WHOOP connected in the background."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (bluetoothReceiverRegistered) {
            // unregisterReceiver throws if it was never registered; the flag guards that, and runCatching
            // covers the rare case the OS already reclaimed it.
            runCatching { unregisterReceiver(bluetoothStateReceiver) }
            bluetoothReceiverRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * One live-HR tick of the lucid-dream trainer.
     *
     * Deliberately thin: it loads the persisted night counters, tracks an approximate sleep onset for
     * the cycle prior, hands everything to [LucidNightRunner], and buzzes if — and only if — the pure
     * policy said to. No decision is taken here.
     */
    private fun tickLucid(hr: Int, now: Long) {
        if (!LucidPrefs.nightEnabled(this)) return

        val prefs = LucidPrefs.of(this)
        val todayKey = java.time.LocalDate.now().toString()

        // A new local date starts a fresh night: counters reset, template reloaded, runner cleared.
        if (lucidNightKey != todayKey) {
            lucidNightKey = todayKey
            lucidRunner.reset()
            lucidAsleepSinceMs = null
            lucidAwakeTicks = 0
            if (prefs.getString(LucidPrefs.NIGHT_KEY, null) != todayKey) {
                prefs.edit()
                    .putString(LucidPrefs.NIGHT_KEY, todayKey)
                    .putInt(LucidPrefs.CUES_TONIGHT, 0)
                    .putInt(LucidPrefs.CUES_THIS_PERIOD, 0)
                    .putLong(LucidPrefs.LAST_CUE_AT, 0L)
                    .putBoolean(LucidPrefs.PERIOD_AROUSAL_ABORTED, false)
                    .apply()
            }
            // Restore the spacing clock so a restart mid-night can't let the ramp fire immediately.
            val lastCue = prefs.getLong(LucidPrefs.LAST_CUE_AT, 0L)
            lucidRunner.restoreLastCueAt(if (lastCue > 0L) lastCue else null)
            lucidTemplate = null
            loadLucidTemplate()
        }

        // Approximate sleep onset: the first sustained under-ceiling stretch. A few high ticks in a row
        // (up and about) clear it; one stray spike does not.
        if (hr > LUCID_SLEEP_CEILING_BPM) {
            lucidAwakeTicks++
            if (lucidAwakeTicks >= LUCID_AWAKE_TICKS_TO_CLEAR) lucidAsleepSinceMs = null
        } else {
            lucidAwakeTicks = 0
            if (lucidAsleepSinceMs == null) lucidAsleepSinceMs = now
        }
        val minutesAsleep = lucidAsleepSinceMs?.let { ((now - it) / 60_000L).toInt() } ?: 0

        val state = LucidCuePolicy.NightState(
            cuesThisPeriod = prefs.getInt(LucidPrefs.CUES_THIS_PERIOD, 0),
            cuesTonight = prefs.getInt(LucidPrefs.CUES_TONIGHT, 0),
            minutesSinceLastCue = null,   // the runner derives this from its own spacing clock
            arousalAbortedPeriod = prefs.getBoolean(LucidPrefs.PERIOD_AROUSAL_ABORTED, false),
        )

        val tick = lucidRunner.onHeartRate(
            hr = hr,
            nowMs = now,
            floorBpm = nightTrough.troughBpm(now),
            template = lucidTemplate,
            minutesAsleep = minutesAsleep,
            state = state,
            enabled = true,   // already gated above; the policy re-checks its own copy
        )

        // Persist whatever the runner decided the counters should now be.
        prefs.edit()
            .putInt(LucidPrefs.CUES_THIS_PERIOD, tick.nextState.cuesThisPeriod)
            .putInt(LucidPrefs.CUES_TONIGHT, tick.nextState.cuesTonight)
            .putBoolean(LucidPrefs.PERIOD_AROUSAL_ABORTED, tick.nextState.arousalAbortedPeriod)
            .apply()

        if (tick.arousalStoodDown) {
            ble.externalLog("Lucid: stirred after the last cue — standing down for this REM period")
        }
        val strength = tick.cue ?: return
        prefs.edit().putLong(LucidPrefs.LAST_CUE_AT, now).apply()
        ble.buzzLucidCue(strength.bursts)
        ble.externalLog(
            "Lucid: ${strength.name.lowercase()} cue fired " +
                "(REM confidence ${"%.2f".format(tick.remConfidence ?: 0.0)}, " +
                "${tick.nextState.cuesTonight}/${LucidCuePolicy.MAX_CUES_PER_NIGHT} tonight)",
        )
    }

    /**
     * Build the personal REM template from recent scored nights.
     *
     * Reads each night's stored hypnogram and the HR underneath it, splits the samples into REM and
     * non-REM SLEEP (wake epochs are EXCLUDED — an awake stretch is high and unstable, exactly what REM
     * looks like on these two features, so counting it would poison the REM class), and hands the
     * per-night summaries to [LiveRemEstimator.learnTemplate], which does the actual learning.
     *
     * Returns null on any shortfall. That is the whole safety story for cold start: no template means
     * the estimator refuses, which means no cue fires.
     */
    private suspend fun buildLucidTemplate(): LiveRemEstimator.RemTemplate? {
        val nowS = System.currentTimeMillis() / 1000L
        val fromS = nowS - LUCID_TEMPLATE_LOOKBACK_DAYS * 86_400L
        val sessions = repo.sleepSessionsMerged("my-whoop", fromS, nowS, limit = 200)
            .filter { !it.stagesJSON.isNullOrBlank() }
            .takeLast(LUCID_TEMPLATE_MAX_NIGHTS)
        if (sessions.isEmpty()) return null

        val samples = ArrayList<LiveRemEstimator.NightSample>(sessions.size)
        for (session in sessions) {
            val segments = com.noop.ui.parsePersistedSegments(session.stagesJSON) ?: continue
            val hr = repo.hrSamples("my-whoop", session.startTs, session.endTs, limit = 20_000)
            if (hr.isEmpty()) continue

            val rem = ArrayList<Double>()
            val nonRem = ArrayList<Double>()
            for (sample in hr) {
                val seg = segments.firstOrNull { sample.ts >= it.start && sample.ts < it.end } ?: continue
                when (seg.stage) {
                    "rem" -> rem.add(sample.bpm.toDouble())
                    // "wake" is deliberately dropped, not bucketed as non-REM.
                    "light", "deep" -> nonRem.add(sample.bpm.toDouble())
                }
            }
            // The night's own sleeping floor, the same reference the live estimate is measured against.
            val floor = (rem + nonRem).minOrNull() ?: continue
            samples.add(LiveRemEstimator.NightSample(remHr = rem, nonRemHr = nonRem, floorBpm = floor))
        }
        return LiveRemEstimator.learnTemplate(samples)
    }

    /**
     * Learn the personal REM template from scored history. Runs off the main thread; leaves
     * [lucidTemplate] null on any shortfall, which makes the estimator stand down rather than guess.
     */
    private fun loadLucidTemplate() {
        scope.launch {
            lucidTemplate = runCatching { buildLucidTemplate() }.getOrNull()
            if (lucidTemplate == null) {
                ble.externalLog(
                    "Lucid: not enough scored nights yet to learn your REM pattern — no cues tonight",
                )
            } else {
                ble.externalLog("Lucid: REM template loaded from ${lucidTemplate?.nights} scored nights")
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "noop_strap_connection"
        private const val NOTIF_ID = 4201
        const val ACTION_STOP = "com.noop.ble.action.STOP_CONNECTION"

        /** How old an alarm-fired stamp may be and still arm the re-buzz watcher. Slightly over the
         *  watcher's own 30-min watch window: a stamp seen late (service restarted, HR resumed) still
         *  arms if a re-buzz could still legitimately fire; anything staler (yesterday's alarm) is
         *  ignored rather than re-armed against a long-gone wake. */
        /** HR at or below this counts as "asleep" for the lucid cycle prior — the same ceiling
         *  [SleepWindowWatcher] uses. */
        /** How far back to look for scored nights when learning the REM template. */
        private const val LUCID_TEMPLATE_LOOKBACK_DAYS = 30L

        /** Cap on nights folded into the template — recent enough to reflect current physiology. */
        private const val LUCID_TEMPLATE_MAX_NIGHTS = 14

        /** HR at or below this counts as "asleep" for the lucid cycle prior — the same ceiling
         *  [SleepWindowWatcher] uses. */
        private const val LUCID_SLEEP_CEILING_BPM = 90

        /** Consecutive above-ceiling ticks before the sleep-onset clock is cleared. */
        private const val LUCID_AWAKE_TICKS_TO_CLEAR = 20

        private const val REBUZZ_STAMP_FRESH_MS = 35 * 60_000L

        /** How far out the re-buzz's one-shot firmware alarm is armed. Long enough for the arm write
         *  to land and the strap to settle; short enough that a false positive (lying still, awake)
         *  costs one dismissable buzz within the minute rather than a delayed surprise. */
        private const val REBUZZ_ALARM_LEAD_S = 60L

        /**
         * Promote the process to the foreground so the strap stays connected. Safe to call when
         * already running. MUST be called from a foreground context (we call it from connect / the
         * Settings toggle) to satisfy Android 12+'s background-start rule. Defensive: any failure is
         * swallowed so it can never break the core connect flow.
         */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WhoopConnectionService::class.java),
                )
            }
        }

        /** Drop the foreground promotion. The connection itself is torn down by the caller. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WhoopConnectionService::class.java)) }
        }
    }
}
