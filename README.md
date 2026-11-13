# GamerLog

> [!WARNING]
> ### ⚠️ Under active development — not ready for use
>
> **GamerLog is unfinished and is not ready for daily use.** It is published for
> development and portfolio purposes only. There is no release, no Play Store
> listing, and no stability promise. Expect incomplete screens, missing features,
> data resets, and breaking changes between commits. Do not install it on a
> device you rely on, and back up anything you care about before trying it.

GamerLog is a local-first Android game playtime tracker. Phase one implements game discovery and local session tracking. Phase two adds daily, weekly, and monthly summaries, a 24×7 activity heatmap, and peak-time analysis. Phase three adds optional one-way Backloggd export; its verification limits are recorded below.

## Implemented

- One Android application module: `:app`.
- Compose UI with Material 3 light and dark themes, plus Android dynamic color where available.
- Daily Summary displaying the local day's total playtime and per-game breakdown with English labels.
- Weekly Summary displaying the current device-local calendar week's total playtime (ISO Monday 00:00 through next Monday 00:00 exclusive) and per-game breakdown with English labels.
- Monthly Summary displaying the current device-local calendar month's total playtime and per-game breakdown.
- Activity statistics for the last 28 device-local calendar days, including today: a Monday–Sunday × 24-hour playtime heatmap and peak-hour/weekday analysis.
- A Settings card for opening Android's usage-access settings.
- A Games catalog of launchable apps. Apps are categorized as games automatically, and users can toggle which apps are tracked.
- Local Room storage for `games`, `play_sessions`, `scan_checkpoints`, and `open_sessions`.
- Usage tracking processes `UsageEvents` deltas when the app resumes and every 15 minutes through WorkManager. Sessions shorter than 15 seconds are ignored, and sessions are split at local midnight.
- Tracking uses no foreground service or wakelock.
- Optional Backloggd export with explicit game mappings, encrypted local credentials, a durable outbox, and connectivity-constrained WorkManager scheduling.

## Backloggd export

1. In **Backloggd**, enter your username and the full `Cookie` request-header value from your signed-in Backloggd browser session. Do not enter your password or share the cookie in chat, logs, or screenshots. Connecting verifies the authenticated identity but does not enable export.
2. Explicitly map each app to its Backloggd game URL or slug. Mapping verifies the public game page; it does not guess from an app's name.
3. Enable export to send mapped sessions started after enabling. **Queue recorded history** is a separate, confirmed action for earlier sessions. Notes may be publicly visible on Backloggd.
4. The outbox retains waiting, sending, sent, excluded, failed, and uncertain records. Disabling or disconnecting stops future work without deleting local sessions or their delivery records. Old-account items remain bound to their original destination.

Local millisecond durations remain authoritative. The existing most-recent playthrough is used; a new one is created only when none exists. Backloggd calendar entries are aggregate representations: same-day contributions update the existing entry by ID rather than posting an overlapping replacement. A versioned note ledger retains each contribution's UUID, original local day, and exact milliseconds. Sub-minute durations accumulate within each day before truncation to whole minutes; preexisting unmanaged minutes, note text, privacy, tags, ranges, and unrelated metadata are retained.

A successful append requires read-back of the new contribution and all prior ledger contributions with the aggregate duration intact. Multiple local outbox items can share one remote journal-entry ID. If delivery is interrupted after dispatch, the record becomes **uncertain**. Future runs inspect Backloggd read-only; an absent marker does not trigger a blind resend. This deliberately favors avoiding duplicate public logs over automatic recovery from an ambiguous request.

Credentials are encrypted with an Android Keystore key and stored in `noBackupFilesDir`, not Room, WorkManager input, or device backups. No plaintext-cookie fallback is used. Expired credentials, bot challenges, or unrecognized responses stop the affected operation rather than being interpreted as empty remote history.

The integration uses Backloggd's undocumented website protocol, not an official API. The first-party [game page](https://backloggd.com/games/celeste/) exposes canonical game metadata; the site's [JavaScript client](https://static.backloggd.com/assets/application-7dc89edc.js) supplies the log-edit and edited-date save protocol. Session notes are described in the [Backloggd changelog](https://backloggd.com/changelog/1_10_0). Website changes can break the integration. Avoid editing the same remote game concurrently with an export: the website does not provide a verified conditional-update/idempotency-key contract.

See [CONTEXT.md](CONTEXT.md) for export-state terminology.

## Current limits and verification

Summary aggregates completed local sessions across the selected day, calendar week, or calendar month, clipping sessions to that interval. Activity groups recorded duration by device-local weekday and hour over the last 28 calendar days, including today; the grid scrolls horizontally on narrow screens. Daylight-saving gaps and repeated hours use actual elapsed duration. These views remain available for recorded history even if usage access is later revoked. Open sessions appear only after tracking finalizes them; the scan model is unchanged.

Heatmap intensity is relative to the busiest weekday/hour cell in the selected 28-day window. Peak hours aggregate the same hour across all weekdays; busiest weekdays aggregate all 24 hours. All positive-duration ties are shown. No peak is reported when there is no recorded playtime.

Earlier Phase 1 device validation remains as recorded: on Android 16, the catalog, tracking toggles, usage-access flow, checkpoints, and repeated scans were exercised. An isolated in-memory Room test captured a real 17.317-second `ACTIVITY_RESUMED` → `ACTIVITY_PAUSED` session and persisted exactly one row; a repeat scan inserted none.

On Android 16, the later phone installation initially required Xiaomi approval; after approval, direct `adb install -r` installed both the debug app (`dev.gamerlog.app`) and its test APK (`dev.gamerlog.app.test`). Both Room DAO instrumentation checks passed (day-boundary clipping and reactive Flow), and the final on-device Compose UI test passed after its selectors were refined: it asserted the fixture title, exact `1m` per-game duration, and day total relative to baseline. The test removed its fixture; afterward, the database still contained the previously scanned games, zero fixture games, and zero play sessions. There was no preexisting app sandbox to lose. The app remains installed; no app clear or uninstall was performed.

Before Usage Access was granted, MainActivity showed the Summary empty state and usage-access prompt with no sessions. The user subsequently reported granting Android Usage Access and seeing a populated Daily Summary with a playtime entry for a tracked game. Game titles and durations are deliberately omitted from this document. This is a user-reported real-usage display, not independently confirmed by ADB; it is distinct from the earlier on-device Compose UI test, which asserted a synthetic fixture title, exact `1m` per-game duration, and day total relative to baseline. The populated UI assertion used Compose semantics; no populated-screen pixel screenshot was captured. The earlier no-access observation describes the state before the grant. These checks do not change the earlier Phase 1 device evidence above. The preceding JVM/SQLite checks are separate from on-device instrumentation.

For the daily Summary slice, `:app:assembleDebug`, `:app:testDebugUnitTest` (18 tests), and `:app:compileDebugAndroidTestKotlin` succeeded. An independent in-memory SQLite smoke check verified overlap clipping, day boundaries, ordering, and duplicate-ignore behavior. Instrumentation has now been run on the phone as described above; no new battery or Doze evidence was collected.

For the weekly slice, three JVM calendar-window tests passed, including both daylight-saving transitions. `:app:assembleDebug` and `:app:assembleDebugAndroidTest` succeeded; the targeted `WeekSummaryUiTest` passed on the Android 16 phone after scrolling its fixture into view. A live Week-chip interaction displayed two games with a nonzero weekly total, confirming that preexisting on-device data is aggregated and rendered; game titles and durations are omitted. Before installation, the app sandbox was backed up; after the test, all preexisting games and sessions remained and the fixture was removed. No connected Gradle test task, app clear, or uninstall was used.

Phase two was completed and checked on an isolated Android 16/API 36 KVM emulator. The VM ran `:app:testDebugUnitTest` (32 tests, zero failures), `:app:assembleDebug`, and `:app:assembleDebugAndroidTest`. The initial connected run passed the other 11 DAO/day/week/month cases but exposed an Activity heatmap test-harness content-ownership error. After correcting that test setup, the targeted `ActivityHeatmapUiTest` passed, including reactive Room updates, exact cell duration, peak insights, and horizontal scrolling. Calendar coverage includes leap February, year rollover, DST gaps/repeated hours, interval clipping, and fractional-offset local hours.

A separate populated-screen smoke scenario installed and launched the debug APK through ADB. A synthetic 90-minute session displayed as `1h 30m` in Month and Activity, with an `18:00` peak hour and correctly shaded 18:00/19:00 cells after horizontal scrolling. Light-theme screenshots showed the populated monthly breakdown and heatmap; a dark-theme screenshot showed the populated Activity totals and peak insights. The scenario restored the emulator database and removed its fixture. These are emulator/fixture observations, not physical-device, battery, or Doze measurements.

The initial phase-three combined VM run passed `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:connectedDebugAndroidTest`: **43 JVM tests and 36 Android tests, zero failures**. Later live-protocol corrections were checked with **13 targeted HTTP transport regressions**, the actual-Android decimal/ledger instrumentation case, and rebuilt debug/test APKs. These targeted checks are not a claim that the entire suite was rerun after every correction.

An ADB UI smoke exercised real public game verification, persisted a canonical mapping without an account connection, and excluded a synthetic waiting item while preserving its local session. Light-theme account/mapping screens and light/dark outbox controls were visually inspected. A physical Android 16 upgrade also preserved all preexisting games and exact session rows; personal-account experiments were then stopped in favor of an isolated disposable account.

The final opt-in live test ran the real Android HTTPS transport, Keystore store, Room outbox, and export engine on the VM. Two synthetic same-day sessions of `65,123 ms` and `125,987 ms` were retained in one Backloggd day entry totaling **3 minutes**, with both exact-duration receipts intact. Both local contributions reached `SENT` and shared the same remote entry ID. After simulating lost persisted acknowledgements, reconciliation and an additional run recovered those same IDs without any extra POST. An independent authenticated read confirmed one day entry, each UUID exactly once, and the correct three-minute total. The separate reconcile-only test mode was intentionally not selected in that successful full live run.

Early live checks exposed real API differences that isolated exchanges had missed: integral decimal numbers, the omitted `game_log` field for an explicit empty log, required playthrough metadata, and same-day replacement behavior. The current day ledger prevents one local session from overwriting another. A fast initial live recovery check hit HTTP 429; the manual driver now spaces requests and stops on limits rather than bypassing them.

Doze behavior and battery use have not been measured; no zero-drain guarantee is made. Backloggd verification must distinguish isolated state-machine/transport checks from an actual authenticated account write.

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

### Opt-in disposable-account live checks

Use only an isolated emulator and a dedicated Backloggd test account. Normal instrumentation runs do not enable these network-writing checks. After building and installing the debug app and Android-test APK, provide a one-use private app-cache file named `backloggd-live-session.json` with JSON fields `username` and `cookieHeader`, or reuse the account already held in the encrypted credential store. The file is deleted immediately after reading; never put the cookie in instrumentation arguments, shell history, logs, or version control. Passwords are not accepted by the driver.

```sh
adb -s "$EMULATOR_SERIAL" shell am instrument -w -r \
  -e class dev.gamerlog.app.core.backloggd.BackloggdLiveExportTest \
  -e backloggdLive true \
  -e backloggdAccount "$BACKLOGGD_TEST_ACCOUNT" \
  -e backloggdGameSlug towerfall-ascension \
  -e backloggdLiveDb gamerlog-live-v2.db \
  dev.gamerlog.app.test/androidx.test.runner.AndroidJUnitRunner
```

The driver uses an isolated on-disk database with the production schema and real HTTPS code. It creates only synthetic contributions, verifies both survive, simulates lost acknowledgements, and checks replay does not POST again. Requests are spaced at least four seconds apart; a rate limit stops the run. Successful local fixtures are cleaned up, while failed/uncertain fixtures remain for inspection and read-only recovery. The test account stays connected with delivery paused; remote synthetic entries remain available for inspection.

To continue an interrupted fixture without sending any new records, use the same account/database arguments, set `backloggdLivePhase=reconcile`, and select `BackloggdLiveExportTest#reconcilesExistingLiveExportsWithoutPosting`. This mode rejects any POST before dispatch. Never retry the fixture-creating phase over retained uncertain work or clear those receipts merely to make a test pass.


## Roadmap

Phase-one session capture and duplicate protection have been observed on a device; Doze and battery behavior remain unmeasured. Remaining roadmap stages:

1. **Dashboard and statistics:** daily, weekly, and monthly summaries, the 24×7 activity heatmap, and peak-time analysis. Verification is recorded above.
2. **Backloggd integration:** optional session/cookie and CSRF-aware export with a durable outbox and connectivity-constrained WorkManager scheduling; live account-write evidence must be recorded separately.
3. **Device validation and hardening:** measure Doze behavior, battery use, and background operation on target Android devices.

A roadmap item should be considered complete only after its behavior and acceptance criteria are implemented and verified. Battery behavior requires device measurement and must not be inferred from the design.

## License

Licensed under the [Apache License 2.0](LICENSE).

Copyright 2026 muwat.
