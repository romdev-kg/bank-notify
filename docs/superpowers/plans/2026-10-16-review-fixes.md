# Review fixes implementation plan

> **For agentic workers:** Use test-driven implementation for independent bug domains; root integrates and sends the entire change to console Claude for independent review.

**Goal:** Fix confirmed actionable findings from the CLI review, verify behavior and publish signed 1.6.2 through GitLab.
**Architecture:** Preserve persistent outbox and settings/signing identity. Correct pure parsing, make retry scheduling bounded and wakeable without concurrent sends, persist bounded diagnostics, and expose actionable service/settings failures.
**Tech Stack:** Existing Kotlin 2.4.21/Android/Room/WorkManager/OkHttp stack.
**Spec:** docs/reviews/2026-10-16-claude-review.md, with user request to fix errors and repeat console Claude review.

## Global constraints
- Preserve applicationId, original signing certificate and all local settings/outbox/history.
- Generic ambiguous transfers remain income; possible duplicates remain preferable to losing equal payments.
- No notification component toggling, unsafe time-window deduplication, automatic key deletion, or queue screen.
- Ordinary retry delay cap defaults to 5 minutes (optional user choice returned no answer); Telegram retry_after is a separate lower bound.
- Only root runs Gradle; agents add tests first and request a RED run before implementation. No agent commits.

## Review focus
- Times/account fragments must never be payment amounts; incoming wording and comments must not be confused with outgoing instructions.
- Wake and normal retry must not send a row concurrently or bypass Telegram rate limits.
- Settings/Keystore failures preserve files and do not crash every app entry point.
- Persistent logs remain bounded and contain no tokens, proxy credentials or raw notification bodies.
- Release signature and upgrade on a real Android runtime are checked before delivery.

## Tasks
- [x] Parser: add regression fixtures for reported time/card/incoming/comment cases and explicit outgoing controls; observe RED; fix parser; GREEN full suite.
- [x] Delivery: add bounded delay and wake/concurrency/rate-limit tests; implement durable scheduling and expose `DeliveryQueue.wake(context)` for saved/imported settings and successful Test. Keep pending rows until confirmed.
- [x] Logging: bounded noBackupFilesDir log with process start/exit diagnostics, synchronized writer, clear/load/rotation tests; `AppLog.initialize(context)` integration owned by root.
- [x] Root diagnostics: elapsed-time disconnected status with settings deep link and clear request-vs-confirmed logs; official access check; last pending delivery error; SMS permission refresh does not disable stored preference.
- [x] Root settings/network: proxy validation on save/test and display after legacy-compatible import (reject unsupported SOCKS credentials), reuse bounded HTTP clients, centralize encrypted preferences and recoverable failure UI without resetting data; remove AppPicker watcher duplication.
- [x] CI: verify pinned signer fingerprint, bump 1.6.2/versionCode11, update docs and explicit findings disposition. No deletion of outbox dedup keys or legacy history.
- [x] Verify: full unit/lint/debug/signed release, upgrade runtime smoke tests, console Claude review of final code; fix confirmed new findings and repeat focused review.
- [x] Commit and normal push to main; verify GitLab tests/release artifact/signature; report exact residual limits and download link.

## Rulings
- Existing review and user request provide scope; no repeated process approval is required.
- Legacy unused history screens/storage and non-critical broad R8 keep rules are not expanded into new product flows. Existing data remains intact; unused-code defects can be removed safely if references confirm no reachable flow.
- Automatic PM toggling and aggressive dedup recommendations were withdrawn by reviewer, not outstanding fixes.

## Final policy refinement
- Legacy proxy values round-trip through encrypted export/import unchanged; the main screen highlights invalid routes. Save/Test remain strict, background delivery skips invalid routes individually. Optional user question received no answer before the documented default was applied.
- CLI re-review closed P1/P2 and both final P3 findings; final report/transcript live under docs/reviews/2026-10-16-claude-fixes-*.md.
