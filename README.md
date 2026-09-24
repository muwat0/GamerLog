# GamerLog

GamerLog is a local-first Android game playtime tracker. Phase one implements game discovery and local session tracking; the broader roadmap, including statistics and optional Backloggd integration, remains in progress.

## Implemented

- One Android application module: `:app`.
- Compose UI with Material 3 light and dark themes, plus Android dynamic color where available.
- A Settings card for opening Android's usage-access settings.
- A Games catalog of launchable apps. Apps are categorized as games automatically, and users can toggle which apps are tracked.
- Local Room storage for `games`, `play_sessions`, `scan_checkpoints`, and `open_sessions`.
- Usage tracking processes `UsageEvents` deltas when the app resumes and every 15 minutes through WorkManager. Sessions shorter than 15 seconds are ignored, and sessions are split at local midnight.
- Tracking uses no foreground service or wakelock.

## Current limits and verification

On Android 16, the catalog, tracking toggles, usage-access flow, checkpoints, and repeated scans were exercised. An isolated in-memory Room test captured a real 17.317-second `ACTIVITY_RESUMED` → `ACTIVITY_PAUSED` session and persisted exactly one row; a repeat scan inserted none. The JVM parser tests pass (18 tests), and the Room Android tests pass (6 tests).

Doze behavior and battery use have not been measured; no zero-drain guarantee is made. Backloggd integration, a broader overview/dashboard, and heatmap/statistics are not implemented.

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

1. **Dashboard and statistics:** daily, weekly, and monthly summaries; a 24×7 activity heatmap and peak-time analysis.
2. **Backloggd integration:** session/cookie and CSRF-aware logging with a durable outbox and WorkManager scheduling.
3. **Device validation and hardening:** measure Doze behavior, battery use, and background operation on target Android devices.

A roadmap item should be considered complete only after its behavior and acceptance criteria are implemented and verified. Battery behavior requires device measurement and must not be inferred from the design.
