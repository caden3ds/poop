# Poop — overhaul plan

A personal, **Android-only** fork of NOOP: fewer screens, less text, a modern One UI 8-inspired
look, and no features the owner doesn't use. Public, but built for one person.

Lineage: Whoop → NOOP → **Poop**. Upstream credit and the PolyForm Noncommercial 1.0.0 licence
stay (required for a fork, and deserved).

---

## Locked decisions

| # | Decision | Consequence |
|---|---|---|
| 1 | **Android only.** Cross-platform parity is abandoned. | Delete `Strand*/`, `Packages/`, `NOOPWatch*/`, `StrandiOS*/`, `project.yml`, `Config/`. Analytics no longer needs Swift twins — changes get much cheaper. |
| 2 | **Identity: `com.hardspace.poop`, name "Poop".** | Android sees a NEW app: installs beside NOOP, starts empty. Re-pair the strap, re-grant permissions, all prior data and settings gone. Accepted. |
| 3 | **Data import removed entirely.** | `ingest/` (8.6k lines) and its screens go. No migration needed — old data isn't being carried over anyway (see #2). Import-priority logic in the merge layer collapses to a single computed source. |
| 4 | **Trends tab removed.** | Keep only the per-metric history the Day screen links into; no standalone trends surface. |
| 5 | **Widget kept**, restyled to the new design. | Used on DeX + phone home screen. Both variants (`NoopGlanceWidget`, `NoopCompactGlanceWidget`). |

### Kotlin package stays `com.noop.**`
`applicationId` (install identity) and the Kotlin package are independent. Renaming 348 files buys
nothing a user can see, and the manifest, services, receivers and providers all reference the current
names. `applicationId` + app name + icon change; the source package does not. Revisit only if it ever
actually itches.

---

## Design language (One UI 8-inspired)

Defined once in `ui/Theme.kt` (`Palette` / `Metrics` / `NoopType`) so screens inherit it:

- **Greyscale-first.** Near-monochrome surfaces, one restrained accent used sparingly for state.
- **Pills everywhere.** Capsule is the default shape; generous corner radii on cards.
- **Subtle gradients.** Soft vertical falloff on elevated surfaces — never decorative colour washes.
- **Real background blur** behind floating elements via `Modifier.blur` / `RenderEffect` on API 31+,
  with a translucent-surface fallback below (minSdk is 26).
- **Restrained type.** Tighter scale, smaller display numerals, far less body copy.
- **No ambient motion.** Nothing loops, sloshes, or drifts.

### The Liquid system is deleted
`LiquidSim.kt`, `LiquidRender.kt`, `LiquidMotion.kt`, `LiquidPrimitives.kt`, `LiquidSky.kt`,
`LiquidScreenSky.kt`, `SceneHeroBackground.kt`, `TimeOfDayBackground.kt` — ~2,150 lines, referenced
by ~12 screens. The animated liquid score vessels and the day-cycle sky are replaced by flat
One UI-style indicators and plain surfaces.

---

## Target information architecture

Three tabs. No More page. Settings is a top-bar button on Day.

```
Day    ← default. date · + quick actions · ⚙ settings
Live   ← replaces Trends
Sleep
```

From 33 destinations down to roughly 8 (3 tabs + settings + metric detail + device wizard +
onboarding + scoring guide).

### Day
- **Three hero numbers:** Charge · Effort · Rest — flat, no liquid.
- **Calendar popup** for day navigation (WHOOP-style; the date picker already exists).
- **Key metrics** — kept and expanded.
- **Mixed cards:** full-width blocks and single-line pill rows.
- **Drag-to-arrange kept** — `TodayLayoutPrefs` already implements it and works.
- **Removed:** Synthesis hero, "Your cards" (`DashboardCards.kt`, customise sheet), Coupled view,
  updates inbox + `UpdateStore`, the avatar→settings icon.
- "Start session" lives in the **+** sheet, not as its own surface.

### Live (replaces Trends)
- Live HR with a rolling graph, live HRV, live stress.
- Also worth surfacing: SpO₂ / skin temperature where the strap provides them, strap battery,
  connection state.
- **HRV streams automatically while the tab is open** — no prompt. Reuses the existing
  `requestRealtimeHr` / `releaseRealtimeHr` lifecycle pattern.
- Absorbs the useful parts of `HrvSnapshotScreen` and `StressScreen`; both are then deleted.

### Sleep
Structure kept — it works. Restyled to the new system. The mark card is already trimmed to two
buttons; manual-sleep mode lives in Settings.

### Settings (one screen)
Profile · Strap & devices · Alarms · Notifications · Automations · Backup & restore · Power ·
Diagnostics (strap log) · About. Absorbs `NotificationsSettingsScreen`, `SmartAlarmScreen`,
`AutomationsScreen`, `DevicesScreen`, `BackupSyncScreen`.

---

## Removal manifest

**Named by the owner**
`ai/` package + `CoachScreen` · `ingest/` + `DataSourcesScreen` + `AppleHealthScreen` ·
`IntelligenceScreen` · `InsightsScreen` + `InsightsHubScreen` · `CompareScreen` ·
`TrendsExploreScreen` · `TrendsScreen` + `TrendsReport` · More page · Synthesis · Your cards ·
updates inbox.

**Agreed on recommendation**
`oura/` + `polar/` (WHOOP-only owner) · `AppChangelog` (2.5k lines of upstream notes) ·
`LabBookScreen` · `RhythmScreen` · `HydrationScreen` · `BreatheScreen` · `IntervalsScreen` ·
`FusedRecordScreen` + `FusionDayAdapter` · `CoupledScreen` · `HealthScreen` and `VitalSignsScreen`
merged into one.

**Kept deliberately**
Workout logging + history (feeds Effort) — reachable from the **+** sheet and a Day card, not its own
tab · per-metric detail/trend screens · onboarding · device wizard · widget.

**Test Centre → replaced by a data export**
Remove the Test Centre entirely (`ui/TestCentreScreen.kt` + the `testcentre/` package, ~2.5k lines)
and replace it with a plain **export of the user's own data to JSON or XML**, shareable off-device.

The point is explicitly to REPLACE in-app AI: rather than embed a coach, export the data and paste it
into whatever model the owner likes. So the export must be self-describing enough for an LLM to
interpret cold — days, nights (with stages), workouts, HRV/RHR series, and the strap log — with units
and field meanings carried in the file, not assumed. Sits in Settings beside the existing
`WhoopCsvExporter` / `RawSensorExport` lanes, which stay.

Note this pulls the strap log out of a "test mode" and into a normal export; keep the log itself, drop
the per-domain test-mode toggles that only ever existed to produce it.

**Two clean side effects**
- Dropping the Coach removes the app's only network use → **`INTERNET` permission and OkHttp go**;
  the app becomes genuinely offline.
- Dropping imports removes the Health Connect dependency.

---

## New feature: Lucid Dream Training — BUILT (untested on hardware)

**Status:** shipped in code, 52 unit tests, **never run against a real strap or a real night.**
`MIN_TRAINING_NIGHTS` is **1** (lowered from 3, 2026-07-27) so the feature works off a single scored
night. Real reduction in confidence, accepted deliberately: with one night the template is that night's
own statistics, so an atypical night becomes the whole model. `isDiscriminating` still refuses a
template whose REM and non-REM look alike. Raise it once more nights are banked.
Files: `analytics/LiveRemEstimator.kt`, `analytics/LucidCuePolicy.kt`, `alarm/LucidNightRunner.kt`,
`alarm/LucidRealityCheckScheduler.kt`, `ui/LucidPrefs.kt`, `WhoopBleClient.buzzLucidCue`, plus the
tick in `WhoopConnectionService` and a Settings section. Both halves default OFF.


A targeted-haptic lucid-dream trainer, plus daytime reality-check conditioning. Two halves:

**1. Night — REM cueing**
- 10–15 min into a REM cycle, fire a **distinct triple micro-pulse** (tap-tap-tap).
- **Ramp:** start gentle; if REM continues with no movement/arousal, wait 90 s and repeat stronger.
- **Budget:** max 1–2 attempts per REM cycle, spaced ≥ 8–10 min apart.
- **Arousal safety cutoff:** watch HR and micro-movement immediately after a cue; on a spike (waking
  up), abort the routine for the rest of that REM cycle.

**2. Day — reality-check conditioning**
- The SAME pattern fires at random times during the day; the user performs a physical reality check.
- Must be unmistakably distinct from the notification buzz and the wake alarm, or the conditioning
  trains the wrong cue.

### Resolved: intensity

The strap exposes `RUN_HAPTICS_PATTERN` as a fixed pattern id + a **loop count** — no amplitude field
(`WhoopBleClient`/`HapticClock`: "a fixed-length motor pulse … we can't vary the on-time per pulse").
**Intensity does not matter** for this feature; distinctness does. So the ramp is expressed as
**salience** (1 loop → 2 loops, and/or wider spacing), and the cue is a **triple micro-pulse**: three
SEPARATE short buzzes ~200 ms apart.

That is already distinct from everything else the motor does: a notification is a single buzz, the wake
alarm is the strap's own continuous firmware alarm (dismissed by double-tap), the fall-back-asleep
re-buzz is one 3-loop buzz, and the Haptic Clock is long/short digit groups with ≥450 ms gaps. Keep it
that way — the whole daytime conditioning half depends on the pattern never being confusable.

### Live REM estimation — the approach

No live staging exists (V1/V2 run post-hoc over offloaded data), so this needs a new **real-time
estimator**. It does NOT need to be as good as the morning hypnogram — the bar is "meaningfully better
than a dumb timer", which is the bar the existing mask-and-4-hour-timer devices fail.

**Method: personal template matching on live HR.**
1. **Training data comes free.** Every scored night already yields a hypnogram with labelled REM
   segments, and the HR samples underneath them. So per-user we can learn what *their* REM looks like.
2. **Features (HR-only, so it works on a sparse live stream):** HR elevation above tonight's own floor
   (the existing `NightTroughTracker` already measures that floor), plus short-window HR variability.
   These separate the stages the way the stagers already assume — deep sits lowest and flattest (V1's
   deep gate keys on 11-min HR flatness), REM sits higher and notably *less* steady.
3. **Timing prior:** cycles run ~90 min and REM lengthens toward morning — reuse the shape of
   `SleepStagerV2.cyclePrior` rather than inventing one.
4. **Cold start is silent.** Below N nights of scored history it does nothing and says so. Never fire a
   cue on a guess it can't support.

**Validation is built in, and comes first.** The morning's post-hoc hypnogram is ground truth for the
night's live guesses, so the estimator can **grade itself every morning**: how many predicted-REM
minutes actually landed in REM. Ship that scorecard BEFORE the haptics do anything — it turns "does
this work?" into a number, and it satisfies the repo's own rule about proving a derived signal tracks a
varying input (a single night that felt right is not validation). Cue firing stays behind a default-off
experimental toggle until the scorecard earns it.

**Integration constraints:** the cue must not collide with the smart alarm or the fall-back-asleep
re-buzz (both already own the motor overnight), and must respect quiet hours. An all-night realtime
stream costs battery — measure it before this can be a nightly default.

## Backend rules

1. **Removal is full-stack.** Every deletion takes its ViewModel state, engine, DAO methods and
   preferences with it in the same pass. No orphaned analytics.
2. **Schema is additive-only.** Leave unused Room tables in place rather than writing destructive
   migrations. Dead schema is free; a bad migration is not.
3. **Measure before optimising.** The analyze loop re-scores 21 days on a cadence and is already
   cached — profile it before touching it.
4. **Split the monsters.** `TodayScreen.kt` (6,158 lines) and `SettingsScreen.kt` (3,179) become
   per-card / per-section files.
5. **Tests stay green.** The JVM suite runs on every phase; pure analytics keeps its coverage.

---

## Phases

Each phase ends **buildable, installable, tested**. No long broken stretches.

- [x] **P1 — Fork hygiene.** `com.hardspace.poop`, name "Poop", v1.0.0. Apple trees, upstream
      changelog/marketing and the Swift CI workflows deleted. README + CLAUDE.md rewritten.
- [x] **P2 — Removals.** (a) AI Coach, Oura, Polar, Garmin, Huami. (b) Intelligence, Insights,
      InsightsHub, Compare, Trends(+Explore/Report), LabBook, Rhythm, Hydration, Breathe, Intervals,
      FusedRecord, Coupled, AppChangelog, WhatsNew, updates inbox. (c) Synthesis, "Your cards",
      "Start session" block on Today. (d) all 13 importers + Health Connect + the OkHttp /
      security-crypto / health-connect deps.
      **Result: ~44k lines removed (133.5k → ~89.8k) and ZERO network permissions in the APK.**
- [x] **P3 — Design system + Liquid removal.** The 8 Liquid files (physics sim, accelerometer tilt,
      per-frame renderer, animated sky, scenic backgrounds) deleted; replaced by flat `Indicators.kt`
      (`ScoreRing` / `MetricBar` / `MetricTrace` / `pressable`, plus the rescued `CountUpText`).
      Palette re-themed to One UI dark: true-black canvas, cards stepping up to `#1C1C1E`, dark-grey
      dial/slider tracks, and light-grey EMPHASISED fills with dark text. **Light mode removed
      entirely** — one fixed scheme, theme pickers gone from Settings and onboarding.
- [x] **P4 — Navigation.** Three tabs (Day · Live · Sleep). More page, drawer groups and
      `MoreSectionPrefs` deleted; Settings reached from the Day top bar (gear, replacing the avatar) and
      linked on to Devices / Alarms / Notifications / Automations so nothing was stranded. Quick actions
      moved to a `+` in the top bar; `QuickAction.route` made nullable so an action can run inline.
- [x] **P5 — Day rebuild.** Data-sources section deleted (with 4 dead DB queries), sleep-debt "On target"
      threshold unified between the tile and the ledger, drag-to-arrange Key Metrics kept, and the
      scroll-jump animation glitch fixed (`animateItemPlacement` was firing outside an active drag).
- [x] **P6 — Live build.** Live HR graph off a new bounded `LiveState.hrRecent` buffer (self-scaling,
      labelled endpoints, min 6 points before it draws at all). New **Autonomic console**: live HRV
      (`SpotHrvReading`, same cleaning + (n-1) RMSSD as the nightly figure) and live stress (Baevsky SI
      via `StressIndex`, which needs no baseline so it reads from the first clean window). Both are
      simply ON while the tab is open — the opt-in 60s snapshot dialog (`HrvSnapshotScreen`) is deleted.
      The daily Stress detail, orphaned when "Your cards" went away in P2c, is re-entered from the
      Autonomic card instead of being deleted; the dead pinned-card block it left behind on Today (a
      whole-history metric scan on every mount, feeding nothing) is gone.
- [x] **P7 — Restyle + design-system cleanup.** The last of the "weird wave UI" plumbing is gone: every
      `topBackground` had been null since P3, so the day-cycle sky prefs, the `fullBleedBackground` flag
      and **two live Settings switches promising a sunrise/dusk sky that no longer existed** were removed,
      and both scaffolds collapsed to their single real path. The near-black `LIQUID_HERO_FILL` hero card
      (copy-pasted across 7 screens, designed to float over that sky and effectively invisible on the
      true-black canvas) now uses the shared token-driven `frostedCardSurface()`. **Light mode is fully
      purged** — `LightTokens`, `ClassicLight`, `Palette.isLight` and all 8 dead branches deleted. Only
      2 hardcoded colours remained app-wide, both in `NoopButton`; filled buttons are now the light-grey
      accent pill with near-black text (the One UI idiom) instead of a hardcoded WHOOP blue.
- [x] **P8 — Settings consolidation.** Reordered into a sane hierarchy — the Devices/Alarms/Notifications/
      Automations hub now sits FIRST instead of buried below Test Centre; identity → display → per-domain
      → data → the diagnostic drawer → About. Three dead controls removed full-stack: **"Trend charts"**
      (written to prefs, read by the Trends tab that P2b deleted), **"Share on-device signals with the
      Coach"** (offered to feed the removed AI Coach and Lab Book), and **"Check for updates"** — which
      called GitHub's releases API in an app with no `INTERNET` permission, so it could only ever fail.
      `UpdateCheck` deleted with it. Stale copy pointing at the removed importers fixed.
- [x] **P9 — Widget restyle.** Both widgets carried their own hardcoded navy/gold palette, duplicated
      between the two files and unrelated to anything on screen. Replaced by `WidgetPalette` /
      `WidgetMetrics`, derived from `DarkTokens`, `Metrics.cardRadius` and `RecoveryScorer`'s own band
      cuts — so the tiles track the app instead of drifting. Dropped a theme probe reading a
      `theme.appearance` pref that no longer exists (it could render a light card under a dark app), and
      the error layout's third hardcoded palette. **Fixed a launch flash**: `surface_base` (the window
      background the system paints before the first Compose frame) was still the pre-overhaul `#060A08`
      while the canvas had become true black. A test now pins colors.xml to the tokens.
- [x] **P10 — Backend consolidation + perf.** Dead code: 3 fully-orphaned files (528 lines) — `HuamiHeartRate`
      (survived P2a), `MarkerCatalog` and `MindSection` — plus the whole unreachable Lab Book DAO/repo API
      (~100 lines). The `labMarker` TABLE is deliberately kept: migrations here are additive, and an unused
      table beats a destructive migration.
      **Perf:** with the importers gone, nothing can write Apple-Health / Health-Connect daily rows, yet
      Today, Health, Steps-calibration and `IntelligenceEngine` were still issuing whole-table scans
      ("0000-01-01".."9999-12-31") against BOTH sources — on every mount and every analysis, guaranteed to
      return nothing. All of them now sit behind `hasImportedDailySources()`: two indexed COUNTs, cached for
      the process. Written as a DATA question rather than a `BuildConfig.ENABLE_DEMO` check so the demo
      flavour and any legacy rows still display.
      **Pairing race (#19) fixed:** auto-reconnect is suppressed while the wizard is picking, the wizard's
      family pick is made authoritative via an explicit `setModel`, and the remembered family is updated so
      the next launch reconnects as the right model instead of re-introducing the mismatch.
- [ ] **P11 — Docs, screenshots, public release.**

---

## Risks

- **One-way divergence.** After this, pulling upstream fixes is manual cherry-picking. Accepted: the
  fork's goals contradict upstream's.
- **Fresh start on install.** Consequence of #2, accepted. Re-pair and re-grant on day one.
- **BLE is untestable off-device.** Nothing in CI or on the JVM validates the strap path. Any change
  touching `ble/` needs a real night on hardware before it's trusted.
- **Blur cost.** Real blur is only free-ish on API 31+; the fallback must not look broken.
