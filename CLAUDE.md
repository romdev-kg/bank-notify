# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug          # Debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # Release APK (ProGuard enabled)
./gradlew installDebug           # Build and install on connected device
./gradlew clean build            # Full clean rebuild
./gradlew testDebugUnitTest      # Run unit tests
./gradlew connectedDebugAndroidTest  # Run instrumented tests on device
```

## Architecture

Android app (Kotlin) that intercepts bank push notifications via `NotificationListenerService` and forwards parsed payment data to Telegram.

### Data Flow

```
Bank notification / SMS → shared BankNotificationParser
  → Room: persistent OutboxMessage
  → DeliveryWorker → TelegramSender → ordered proxy failover → Telegram Bot API
  → mark delivered only after Telegram confirms message_id
  → failures remain queued; WorkManager retries with backoff and retry_after
```

### Key Components

- **`BankNotificationListener`** — core service. System-managed `NotificationListenerService`, not a regular foreground service. Uses `requestRebind()` for auto-reconnection after reboot or process death. Static `isConnected` flag tracks binding state.
- **`BootCompleteReceiver`** — calls `requestRebind()` on device boot to restore the listener without manual permission toggle.
- **`TelegramSender`** — sends messages via Bot API with retry logic. Reads token/chatId from `EncryptedSharedPreferences`.
- **`BankAppsManager`** — manages which installed apps are tracked and enabled. Uses encrypted prefs, keyed by package name.
- **`AppLog`** — in-memory ring buffer (500 entries) for debugging, displayed in `LogsActivity`.

### Storage

- **Credentials**: `EncryptedSharedPreferences` (AES256, key alias `bank_notify`)
- **Storage**: Room database (`BankNotificationsDatabase`), legacy `BankNotification` history and active `OutboxMessage` delivery queue (schema v2). Production intake currently writes only the outbox; raw notification text is not persisted.
- **App tracking config**: `EncryptedSharedPreferences` via `BankAppsManager`

## Project Conventions

- Language: Kotlin 2.4.21 (AGP built-in Kotlin), JVM target 17
- Min SDK 26 (Android 8.0), target SDK 37, compile SDK 37.2; build JDK 25, Gradle 9.7.0
- View system with ViewBinding (no Compose)
- Coroutines for async (IO dispatcher for network/DB, Main for UI)
- KSP for Room annotation processing
- All UI strings in Russian
- Commit messages in English

## Android-Specific Notes

- The `NotificationListenerService` requires manual user permission grant in system settings — it cannot be requested programmatically
- Notification filter meta-data in manifest: accepts `conversations|alerting|silent|ongoing`; do not disable silent bank payments
- ProGuard keeps all `com.banknotify.**` classes (broad keep rule in `proguard-rules.pro`)
- `QUERY_ALL_PACKAGES` permission is needed for app picker to list all installed apps on Android 11+

## Delivery

GitLab is primary CI: verify tests/lint/debug, then signed release on protected main/tags.
The existing keystore is stored only in protected/masked CI variables. Preserve signing identity and applicationId.
GitHub is a mirror with a manual fallback workflow; never force-push.
System backup excludes encrypted preferences and payment database; use password-encrypted settings export for device transfers.
