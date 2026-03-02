# Changelog

## [2026-03-02] Автоматическое переподключение NotificationListener

### Исправлено
- NotificationListenerService не переподключался после перезагрузки устройства или перезапуска приложения
- Требовалось вручную снимать и заново давать права на чтение уведомлений

### Добавлено
- `onListenerConnected()` / `onListenerDisconnected()` в BankNotificationListener с автоматическим `requestRebind()`
- `BootCompleteReceiver` теперь вызывает `requestRebind()` при загрузке устройства
- `MainActivity` вызывает `requestRebind()` при открытии приложения, если listener отключён
- Статическое поле `isConnected` для отслеживания состояния подключения listener'а
