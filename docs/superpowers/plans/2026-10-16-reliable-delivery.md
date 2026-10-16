# Reliable notification delivery

> Execute inline with test-driven-development and verification-before-completion.

**Goal:** Reconnect the notification listener automatically and retain captured payment messages until Telegram confirms delivery.

**Architecture:** A process-wide reconnect loop uses exponential delays (5 seconds to 5 minutes); WorkManager provides persistent recovery after process death. A Room outbox stores each event before network I/O. Unique WorkManager jobs deliver individual rows, retry with exponential backoff, and respect Telegram retry_after. A periodic recovery job repairs the gap between database insertion and job scheduling.

**Constraints:** Keep application ID and signing configuration. Migrate database v1 without deleting history or encrypted preferences. Keep Kotlin, Room and existing proxy support. User chose retry after ambiguous network outcomes, accepting possible Telegram duplicates. Work in and push to the current branch.

**Review focus:** Interrupted delivery; duplicate callbacks versus separate identical payments; malformed/negative Telegram responses; permission revocation; process death between persistence and scheduling.

## Tasks

- [x] Test and implement a reconnect loop: only one loop, increasing capped delays, reset on success, stop when notification access is revoked. Wire service lifecycle, boot, app startup and live status.
- [x] Test and implement a Room outbox and v1→v2 migration. Deduplicate by source event identity, preserve failed rows across reopen, never discard an unconfirmed payment.
- [x] Test and implement delivery confirmation parsing and durable retry workers. Validate HTTP status plus Telegram ok/result, honor retry_after, recover unscheduled rows at startup/boot and periodically. Capture push and SMS through the same queue.
- [x] Run unit tests, debug assembly and lint. Review the complete diff, update README/changelog and version, commit task files and push normally.

## Validation

Use the existing local Android build image `sms-forwarder-build:1` with the repository mounted at `/workspace`. Run `bash ./gradlew testDebugUnitTest assembleDebug lintDebug --console=plain`. Tests use coroutine virtual time for retries, Robolectric/Room for persistence/migration, and controlled HTTP responses for Telegram confirmation. Physical-device background restrictions remain a manual verification item.

## Accepted follow-up scope

- Password-encrypted settings export/import, with type validation and no import of queue/history or system permissions.
- Ordered proxy list with failover, preserving the existing single-proxy preference as the first entry.
- Aggregate skipped-event counters and a bounded log.
- User accepted diagnostics and configurable external healthcheck from SMS Forwarder; declined delivery queue UI and custom filter rules.
- Version 1.6.0 (code 9); retain release signing and package ID.

## Review fixes

- Measure Telegram retry_after from receipt of response, including proxy failover time.
- Persist reconnect successors with explicit capped delays instead of WorkManager's built-in five-hour exponential ceiling.
- Validate healthcheck URL with the same parser as the HTTP client.

## Verification record

- Regression tests observed failing before reconnect, confirmation parsing, outbox delivery, encrypted backup and review fixes were implemented.
- Final `testDebugUnitTest assembleDebug lintDebug`: BUILD SUCCESSFUL, 23 tests, zero failures/errors.
- Independent read-only review completed; all three reported correctness findings addressed. Additional proxy HTTP 407 failover regression covered.
- Real-phone OEM battery restrictions and live Telegram delivery were not exercised.
- Implementation and tests ran inline; a separate reviewer inspected the full changes.
