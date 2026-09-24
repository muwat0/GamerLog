# GamerLog Agent Guide

## Project status

GamerLog is an Android application with one Gradle module, `:app`. The implemented first phase includes a Compose Material 3 interface, a launchable-app game catalog with tracking toggles, local Room storage, and usage-event session tracking. See `README.md` and the current in-repository code for the implemented behavior; do not rely on external specifications or assume roadmap items already exist.

The current phase is local game discovery and session tracking. Dashboard summaries, heatmap/statistics, Backloggd integration, and device/Doze/battery hardening remain future work. Keep changes within the existing module structure unless a concrete requirement justifies a change.

## Product boundaries

GamerLog is local-first: local records are the source of truth, and Backloggd is an optional future one-way export rather than a replacement for local sessions. Do not add social/server features, a live in-game HUD, or importing playtime from external platforms without an explicit product decision. Do not add telemetry or analytics that expose game history or credentials.

Tracking uses Android usage access and `UsageEvents`; do not assume data is available unless the user grants that access. Do not add a continuously running `ForegroundService`, persistent tracking service, `WakeLock`, or accessibility service as the tracking solution. The current implementation scans on app resume and periodically through WorkManager. Do not describe the zero-battery-drain goal as a measured guarantee.

## UI and language

Use the existing Jetpack Compose Material 3 patterns and support system light/dark appearance. Dynamic color may be used where supported. English is the default UI language; do not add in-app language selection or multilingual infrastructure without an explicit decision.

## Tracking and data invariants

- Process usage-event deltas for tracked packages and safely handle missing closing events and repeated/overlapping scan intervals.
- Ignore sessions shorter than 15 seconds, and split sessions crossing midnight according to the device-local day.
- Persist sessions and their scan checkpoint consistently so interruptions or replaying a delta do not create duplicate sessions. Inspect the existing Room schema and tracking code before changing persistence behavior; do not invent fields or identifiers.
- Keep the game catalog, sessions, open-session state, and scan checkpoints in local storage. Preserve existing relationships and deletion behavior when modifying the data model.

## Synchronization invariants

Backloggd synchronization is not implemented. If implementing it after an explicit product decision, keep it a one-way export from local sessions. Use a durable outbox with explicit persistent states for waiting, successfully sent, excluded, and failed records; offline or failed sends must not lose local records, retries must not log a session twice, and success is recorded only after acknowledgement. Schedule sends only when connectivity is available. Store credentials locally and securely; never write them to logs or analytics. Keep network integration out of tracking and statistics code.

## Roadmap and completion

1. **Current phase — discovery and tracking:** game discovery, user tracking toggles, usage-access flow, local sessions/checkpoints, and duplicate protection.
2. **Dashboard and statistics:** daily/weekly/monthly summaries, a 24×7 activity heatmap, and peak-time analysis.
3. **Backloggd integration:** session/cookie and CSRF-aware logging with a durable outbox and connectivity-aware scheduling.
4. **Device validation and hardening:** measure Doze behavior, battery use, and background operation on target devices.

Do not mark a phase complete until its behavior is implemented and evidenced. Keep changes consistent with the current `:app` structure; do not add speculative modules or duplicate layers.

## Verification

Inspect the repository and relevant call sites before changing behavior. Use the available Gradle wrapper and module tasks for code changes, selecting the narrowest test or smoke scenario that exercises the change. Tracking coverage should include the 15-second boundary, missing events, midnight splitting, and replay/idempotency. Future synchronization coverage should include state transitions and offline/connectivity-return behavior. Connected Android instrumentation tests may uninstall the app and erase its sandbox: use an isolated emulator or back up app data first. Report device, Doze, or battery claims only when measured on a real device; a unit test is not device evidence.
