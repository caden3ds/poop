# CLAUDE.md — working on Poop

Poop is an **Android-only**, offline, on-device WHOOP companion app. A personal fork of NOOP, being
overhauled per [OVERHAUL.md](OVERHAUL.md) — read that for the target shape and the current phase.

## Hard scope limits

- **No network.** There is no `INTERNET` permission and nothing may add one. No server, no account,
  no cloud sync, no telemetry, no crash reporting.
- **No WHOOP code, firmware, assets, or DRM circumvention.** This is clean-room interoperability with
  hardware the user owns. Keep it that way.
- **Android only.** Do not reintroduce Swift/iOS/macOS targets or cross-platform parity obligations.
- Contributions are under [PolyForm Noncommercial 1.0.0](LICENSE), inherited from upstream.

## Layout

Everything lives in `android/`. Root package `com.noop.**` (the *source* package — the install
identity is `com.hardspace.poop`; they are deliberately different and the source package is not worth
renaming).

| Package | What lives here |
|---|---|
| `protocol/` | BLE framing, CRC, command/event/packet decode. Pure — no Android deps, fully unit-tested. |
| `ble/` | `WhoopBleClient` (GATT), `WhoopConnectionService` (foreground service), scanning, offload. |
| `data/` | Room entities, DAOs, `WhoopRepository`, device registry. |
| `analytics/` | HRV / recovery / strain / sleep math. Pure and DB-free — this is where correctness lives. |
| `ui/` | Compose screens, `AppViewModel`, design system (`Theme.kt`, `Components.kt`). |
| `alarm/`, `notif/`, `widget/` | Alarms + re-buzz, notifications, Glance widgets. |

## Build & test

```bash
cd android
./gradlew assembleFullDebug          # APK
./gradlew testFullDebugUnitTest      # JVM tests — no device needed
./gradlew compileFullDebugKotlin     # fast compile check
```

On Windows: use JDK 17+ (Android Studio's bundled JBR works; a system JDK 25 does **not** — Gradle
8.7 rejects it). Dependency verification is on, so a first build on a new platform may need
`--write-verification-metadata sha256`.

**What CI does not cover:** BLE. Nothing on the JVM or in CI validates the strap path — compiling
proves nothing about connection behaviour. Anything touching `ble/` needs a real strap and, for
overnight features, a real night. Say what was tested on hardware.

## Rules that matter

- **Analytics changes need a test.** `analytics/` is pure by design; keep it that way and pin
  behaviour with JVM tests. Physiological outputs are approximations — never present them otherwise.
- **Design tokens only.** Colours, type and spacing come from `Palette` / `Metrics` / `NoopType`.
  No hardcoded hex, sp, or dp-by-feel.
- **Migrations are additive.** Add a versioned Room migration with a test; never mutate an existing
  one. Prefer leaving an unused table over writing a destructive migration.
- **Removal is full-stack.** Deleting a feature takes its ViewModel state, engine, DAO methods and
  preferences with it. No orphaned analytics.
- **BLE safety.** Never add destructive/write commands to hardware. CRC-gate inbound frames. Protocol
  facts belong in the decoders, not as hex literals in app code.
- **Don't fabricate a reading.** If a value can't be computed honestly, show nothing and say why.
  A null is better than an invented number.

## Voice

Short. Neutral. No marketing copy, no emoji in the UI, no walls of explanatory text on screens.
Comments explain *why*, not *what*.
