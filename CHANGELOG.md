# Changelog

## [2026-03-04] SMS-перехват банковских сообщений

### Добавлено
- `BankSmsReceiver` — перехват SMS от Сбербанка (900) и Альфа-Банка (Alfa-Bank)
- Парсинг сумм зачислений из SMS с отправкой в Telegram с меткой `[SMS]`
- Переключатель SMS-перехвата в интерфейсе с запросом runtime-разрешения `RECEIVE_SMS`

### Исправлено
- Логи обновлялись каждые 2 сек и сбрасывали скролл — убрано авто-обновление, оставлена кнопка «Обновить»

## [2026-03-03] Настройка release signing

### Добавлено
- Release signing конфигурация в `app/build.gradle.kts` (читает из `signing.properties` локально или env vars в CI)
- Keystore файл `banknotify-release.jks` для подписи release APK
- `signing.properties` для локальной сборки (в `.gitignore`)
- Шаг decode keystore в GitHub Actions workflow

### Изменено
- `build.yml`: release APK теперь подписан (вместо `app-release-unsigned.apk` → `app-release.apk`)

## [2026-03-02] Автоматическое переподключение NotificationListener

### Исправлено
- NotificationListenerService не переподключался после перезагрузки устройства или перезапуска приложения
- Требовалось вручную снимать и заново давать права на чтение уведомлений

### Добавлено
- `onListenerConnected()` / `onListenerDisconnected()` в BankNotificationListener с автоматическим `requestRebind()`
- `BootCompleteReceiver` теперь вызывает `requestRebind()` при загрузке устройства
- `MainActivity` вызывает `requestRebind()` при открытии приложения, если listener отключён
- Статическое поле `isConnected` для отслеживания состояния подключения listener'а
