# GamerLog

> [!WARNING]
> ### ⚠️ Under active development — not ready for use
>
> **GamerLog is unfinished and is not ready for daily use.** It is published for
> development and portfolio purposes only. There is no release, no Play Store
> listing, and no stability promise. Expect incomplete screens, missing features,
> data resets, and breaking changes between commits. Do not install it on a
> device you rely on, and back up anything you care about before trying it.

GamerLog is a local-first Android game playtime tracker. Phase one implements game discovery and local session tracking. Phase two is in progress: the daily and device-local weekly Summary slices are implemented; monthly summary, broader statistics, and optional Backloggd integration remain future work.

## Implemented

- One Android application module: `:app`.
- Compose UI with Material 3 light and dark themes, plus Android dynamic color where available.
- Daily Summary displaying the local day's total playtime and per-game breakdown with English labels.
- Weekly Summary displaying the current device-local calendar week's total playtime (ISO Monday 00:00 through next Monday 00:00 exclusive) and per-game breakdown with English labels.
- A Settings card for opening Android's usage-access settings.
- A Games catalog of launchable apps. Apps are categorized as games automatically, and users can toggle which apps are tracked.
- Local Room storage for `games`, `play_sessions`, `scan_checkpoints`, and `open_sessions`.
- Usage tracking processes `UsageEvents` deltas when the app resumes and every 15 minutes through WorkManager. Sessions shorter than 15 seconds are ignored, and sessions are split at local midnight.
- Tracking uses no foreground service or wakelock.

## Current limits and verification

The daily Summary displays the local day's total playtime and per-game totals with English labels. The weekly Summary displays the current device-local calendar week's playtime (ISO Monday 00:00 through next Monday 00:00 exclusive) and per-game breakdown, aggregated and clipped via Room across the interval. Monthly summary, the 24×7 activity heatmap, and peak-time analysis remain placeholders; the scan model is unchanged.

Earlier Phase 1 device validation remains as recorded: on Android 16, the catalog, tracking toggles, usage-access flow, checkpoints, and repeated scans were exercised. An isolated in-memory Room test captured a real 17.317-second `ACTIVITY_RESUMED` → `ACTIVITY_PAUSED` session and persisted exactly one row; a repeat scan inserted none.

On Android 16, the later phone installation initially required Xiaomi approval; after approval, direct `adb install -r` installed both the debug app (`dev.gamerlog.app`) and its test APK (`dev.gamerlog.app.test`). Both Room DAO instrumentation checks passed (day-boundary clipping and reactive Flow), and the final on-device Compose UI test passed after its selectors were refined: it asserted the fixture title, exact `1m` per-game duration, and day total relative to baseline. The test removed its fixture; afterward, the database still contained the previously scanned games, zero fixture games, and zero play sessions. There was no preexisting app sandbox to lose. The app remains installed; no app clear or uninstall was performed.

Before Usage Access was granted, MainActivity showed the Summary empty state and usage-access prompt with no sessions. The user subsequently reported granting Android Usage Access and seeing a populated Daily Summary with a playtime entry for a tracked game. Game titles and durations are deliberately omitted from this document. This is a user-reported real-usage display, not independently confirmed by ADB; it is distinct from the earlier on-device Compose UI test, which asserted a synthetic fixture title, exact `1m` per-game duration, and day total relative to baseline. The populated UI assertion used Compose semantics; no populated-screen pixel screenshot was captured. The earlier no-access observation describes the state before the grant. These checks do not change the earlier Phase 1 device evidence above. The preceding JVM/SQLite checks are separate from on-device instrumentation.

For the daily Summary slice, `:app:assembleDebug`, `:app:testDebugUnitTest` (18 tests), and `:app:compileDebugAndroidTestKotlin` succeeded. An independent in-memory SQLite smoke check verified overlap clipping, day boundaries, ordering, and duplicate-ignore behavior. Instrumentation has now been run on the phone as described above; no new battery or Doze evidence was collected.

For the weekly slice, three JVM calendar-window tests passed, including both daylight-saving transitions. `:app:assembleDebug` and `:app:assembleDebugAndroidTest` succeeded; the targeted `WeekSummaryUiTest` passed on the Android 16 phone after scrolling its fixture into view. A live Week-chip interaction displayed two games with a nonzero weekly total, confirming that preexisting on-device data is aggregated and rendered; game titles and durations are omitted. Before installation, the app sandbox was backed up; after the test, all preexisting games and sessions remained and the fixture was removed. No connected Gradle test task, app clear, or uninstall was used.

Doze behavior and battery use have not been measured; no zero-drain guarantee is made. Backloggd integration remains unimplemented.

## Build and test

Requirements:

- JDK 17, selected through the `JAVA_HOME` environment variable. Gradle requires JDK 17; do not hardcode a machine-specific JDK path in the project.
- Android SDK with Android SDK Platform 36 installed and accepted SDK licenses.
- A configured SDK location, such as `ANDROID_HOME`/`ANDROID_SDK_ROOT` or `local.properties` with `sdk.dir`.

Set `JAVA_HOME` in your shell to your JDK 17 installation, then run from the repository root:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

The unit-test task runs JVM tests; the connected Android test task requires a device or emulator. **Use an isolated emulator or back up GamerLog's app data before connected instrumentation tests:** connected test execution can uninstall the target package and erase its sandbox, making the test task unsafe for an unbacked-up device installation. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Roadmap

Phase-one session capture and duplicate protection have been observed on a device; Doze and battery behavior remain unmeasured. Remaining roadmap stages:

1. **Dashboard and statistics:** daily and weekly (device-local Monday through next Monday) summaries are implemented. Monthly summary, the 24×7 activity heatmap, and peak-time analysis remain future work.
2. **Backloggd integration:** session/cookie and CSRF-aware logging with a durable outbox and WorkManager scheduling.
3. **Device validation and hardening:** measure Doze behavior, battery use, and background operation on target Android devices.

A roadmap item should be considered complete only after its behavior and acceptance criteria are implemented and verified. Battery behavior requires device measurement and must not be inferred from the design.

## License

Licensed under the [Apache License 2.0](LICENSE).

Copyright 2026 muwat.
