# GamerLog Agent Guide

## Project status

GamerLog is an Android application with one Gradle module, `:app`. The implemented first phase includes a Compose Material 3 interface, a launchable-app game catalog with tracking toggles, local Room storage, and usage-event session tracking. See `README.md` and the current in-repository code for the implemented behavior; do not rely on external specifications or assume roadmap items already exist.

Dashboard statistics include daily/weekly/monthly summaries and a 24×7 activity heatmap with peak-time analysis. Optional Backloggd export uses the same local source of truth; see `README.md` for setup, verification evidence, and remaining limitations. Device/Doze/battery hardening remains future work. Keep changes within the existing module structure unless a concrete requirement justifies a change.

## Product boundaries

GamerLog is local-first: local records are the source of truth, and Backloggd is an optional one-way export rather than a replacement for local sessions. Do not add social/server features, a live in-game HUD, or importing playtime from external platforms without an explicit product decision. Do not add telemetry or analytics that expose game history or credentials.

Tracking uses Android usage access and `UsageEvents`; do not assume data is available unless the user grants that access. Do not add a continuously running `ForegroundService`, persistent tracking service, `WakeLock`, or accessibility service as the tracking solution. The current implementation scans on app resume and periodically through WorkManager. Do not describe the zero-battery-drain goal as a measured guarantee.

## UI and language

Use the existing Jetpack Compose Material 3 patterns and support system light/dark appearance. Dynamic color may be used where supported. English is the default UI language; do not add in-app language selection or multilingual infrastructure without an explicit decision.

## Tracking and data invariants

- Process usage-event deltas for tracked packages and safely handle missing closing events and repeated/overlapping scan intervals.
- Ignore sessions shorter than 15 seconds, and split sessions crossing midnight according to the device-local day.
- Persist sessions and their scan checkpoint consistently so interruptions or replaying a delta do not create duplicate sessions. Inspect the existing Room schema and tracking code before changing persistence behavior; do not invent fields or identifiers.
- Keep the game catalog, sessions, open-session state, and scan checkpoints in local storage. Preserve existing relationships and deletion behavior when modifying the data model.

## Synchronization invariants

Backloggd export is opt-in, with explicit game mappings and separate consent for recorded history. Keep it one-way from local sessions. The durable outbox is the sole delivery-state authority; do not restore the obsolete `play_sessions.syncStatus` field. Preserve waiting, sending, sent, excluded, failed, and uncertain records across account changes and interruptions. An interrupted POST may already have committed: reconcile the unique marker read-only, and never resend an uncertain item merely because the marker is absent. Record success only after acknowledgement/read-back. Schedule ordinary, connectivity-constrained WorkManager jobs; keep transport out of tracking and statistics. Store cookies with Android Keystore encryption in `noBackupFilesDir`; never log them or place them in Room/WorkManager data. Session notes may be public, and local exact durations must not be replaced by rounded remote minutes. See `CONTEXT.md` for state definitions.

Local outbox items are contributions, not separate remote calendar entries. Merge same-day contributions into the existing remote entry and retain every UUID/day/exact-duration ledger item. Truncate only after summing local milliseconds per day; preserve unmanaged remote time and existing note/privacy/tag/range/review data. Read-back must confirm prior contributors remain, not merely the latest marker. Shared remote IDs across multiple sent local rows are expected. The user selected the existing most-recent playthrough, not an automatically added dedicated playthrough.

## Roadmap and completion

1. **Discovery and tracking:** game discovery, user tracking toggles, usage-access flow, local sessions/checkpoints, and duplicate protection.
2. **Dashboard and statistics:** daily/weekly/monthly summaries, a 24×7 activity heatmap over the last 28 device-local calendar days including today, and peak-time analysis. Aggregate actual recorded duration and preserve correct local-calendar boundaries across time-zone and DST changes.
3. **Backloggd integration:** session/cookie and CSRF-aware logging with a durable outbox and connectivity-aware scheduling.
4. **Device validation and hardening:** measure Doze behavior, battery use, and background operation on target devices.

Do not mark a phase complete until its behavior is implemented and evidenced. Keep changes consistent with the current `:app` structure; do not add speculative modules or duplicate layers.

## Verification

Inspect the repository and relevant call sites before changing behavior. Use the available Gradle wrapper and module tasks for code changes, selecting the narrowest test or smoke scenario that exercises the change. Tracking coverage should include the 15-second boundary, missing events, midnight splitting, and replay/idempotency. Synchronization coverage should include migrations, state transitions, offline/connectivity-return behavior, lost acknowledgement, account isolation, and concurrent-worker duplicate protection. Connected Android instrumentation tests may uninstall the app and erase its sandbox: use an isolated emulator or back up app data first. Keep live account-write evidence separate from isolated transport/state-machine tests; obtain explicit approval before publishing test journal entries. Report device, Doze, or battery claims only when measured on a real device; a unit test is not device evidence.
