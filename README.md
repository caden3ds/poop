<img src="docs/logo.svg" alt="" width="88" align="left" hspace="16" vspace="4">

# Poop

An offline, on-device companion app for WHOOP straps. **Android only.**

Whoop → NOOP → Poop. This is a personal fork of [NOOP](https://github.com/NoopApp/noop), rebuilt
for one person's use: fewer screens, less text, a modern greyscale UI, and none of the features its
owner doesn't use. It's public because there's no reason for it not to be — but it is not trying to
be a general-purpose app, and it takes opinions upstream would not.

## What it does

Pairs directly with a WHOOP strap over Bluetooth, stores everything in on-device SQLite, and computes
Charge (recovery), Effort (strain), Rest (sleep), HRV, resting HR and sleep stages **locally**.

No account. No cloud. No telemetry. No network permission at all.

## What's different from NOOP

- **Android only** — the Swift/iOS/macOS/watchOS targets are gone.
- **Three tabs**: Day · Live · Sleep. No More page, no nested hubs.
- **No AI Coach, no data import, no Oura/Polar** — and therefore no `INTERNET` permission.
- **New look** — flat greyscale, pills, subtle depth. The animated "Liquid Metal" theme is removed.
- Its own install identity (`com.hardspace.poop`), so it sits beside NOOP rather than upgrading it.

### Added here

- **Live tab** — live heart rate with a self-scaling graph, plus live HRV and live stress computed
  continuously from the R-R stream while the tab is open. No "start a reading" prompt.
- **Manual sleep mode** — turn off automatic detection and log every night yourself; the window is
  still scored from the strap's recorded data.
- **Fall-back-asleep re-buzz** — if you drop back to your sleeping heart rate after the alarm, the
  strap's own firmware alarm is re-armed.
- **Lucid dream training** — REM-cued haptics overnight plus daytime reality-check cues. See below.

See [OVERHAUL.md](OVERHAUL.md) for the full plan and its current state.

## Lucid dream training

Opt-in, off by default, and the most experimental thing here.

At night it estimates REM from live heart rate — elevation above the night's own sleeping floor plus
short-window variability, matched against a template learned from your **own** scored nights — and
fires a distinct triple buzz. It is bounded on purpose: at most 6 cues a night, 2 per REM period, at
least 8 minutes apart, and it stands down for the rest of a period if your heart rate says you
stirred. Below the minimum number of scored nights it does nothing and says so.

By day the same pattern fires at random times inside a waking window, so you learn to recognise it.
That is what gives the night cue something to trigger.

**This deliberately buzzes a sleeping person.** The restraint rules carry the tests, not the cueing.

## Status and honesty

This is a personal fork under active change. Specifically:

- The pure analytics are well covered — 2,600+ JVM tests, and `analytics/` is DB-free by design.
- **Nothing in CI touches Bluetooth.** A green build says nothing about connection behaviour; the
  strap path can only be validated on real hardware, and overnight features need a real night.
- Lucid dream training has not yet been through a full night on hardware.
- Physiological outputs are approximations. Where a value cannot be computed honestly the app shows
  nothing and says why, rather than inventing a number.

## Build

Needs JDK 17+, the Android SDK (API 34, build-tools 34.0.0), and a device on Android 8.0+.

```bash
cd android && ./gradlew assembleFullDebug
```

The APK lands at `android/app/build/outputs/apk/full/debug/app-full-debug.apk`. A `demo` flavour
builds with synthetic data and no strap: `./gradlew assembleDemoDebug`.

Run the JVM tests with `./gradlew testFullDebugUnitTest` — no device needed.

Bluetooth cannot be tested in an emulator — the strap path needs real hardware.

## Credit

Built on [NOOP](https://github.com/NoopApp/noop) and [@ryanbr's fork](https://github.com/ryanbr/noop),
which did the hard work: the clean-room WHOOP protocol, the analytics, and the Android app this
started from. Their prior-art credits (`johnmiddleton12/my-whoop`, `b-nnett/goose`) are preserved in
[ATTRIBUTION.md](ATTRIBUTION.md) and [NOTICE](NOTICE).

Licensed under [PolyForm Noncommercial 1.0.0](LICENSE), inherited from upstream.

## Not affiliated with WHOOP. Not a medical device.

"WHOOP" identifies the hardware this interoperates with; this project contains no WHOOP code,
firmware or assets. Every output — HR, HRV, recovery, strain, sleep, SpO₂, temperature — is an
approximation and is **not** clinically validated. See [DISCLAIMER.md](DISCLAIMER.md).
