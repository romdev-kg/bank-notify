# BankNotify

Android-приложение для перехвата push-уведомлений из банковских приложений (Сбербанк, Россельхозбанк) и отправки данных платежей в Telegram-группу.

## Функциональность

- Перехват push-уведомлений из Сбербанка и Россельхозбанка
- Автоматический парсинг суммы, типа операции, номера счёта
- Отправка в Telegram Bot API
- Локальное логирование всех операций (Room Database)
- История последних 50 операций
- Защита токена Telegram (EncryptedSharedPreferences)
- Запуск при перезагрузке устройства

## Требования

- Android 8.0+ (API 26+)
- Android Studio 2024.1+
- JDK 17

## Установка

### Вариант 1: Из GitHub Actions (облачная сборка)

1. Клонируй репо:
```bash
git clone https://github.com/your-username/bank-notify.git
cd bank-notify
```

2. Создай новый Release с тегом:
```bash
git tag v1.0.0
git push origin v1.0.0
```

3. Дождись завершения GitHub Actions

4. Скачай APK из Artifacts или Releases

### Вариант 2: Локальная сборка

1. Открой проект в Android Studio

2. Подожди синхронизацию Gradle

3. Собери APK:
```bash
./gradlew assembleDebug
```

4. APK будет в: `app/build/outputs/apk/debug/app-debug.apk`

## Настройка

### 1. Включить разрешение "Слушатель уведомлений"

На телефоне:
```
Параметры → Приложения → Особые разрешения → Слушатель уведомлений → BankNotify → Включить
```

### 2. Ввести токен Telegram и Chat ID

В приложении:
1. Нажми **Настройки**
2. Введи Telegram Token и Chat ID
3. Нажми **Сохранить**

### 3. Протестировать

Нажми **Тестировать отправку** — должно прийти сообщение в Telegram

## Стек технологий

- Kotlin 2.0.21
- Android API 35 (Android 15)
- Coroutines 1.8.1
- Room 2.6.1
- OkHttp 4.12.0
- Jetpack Libraries

## Структура проекта

```
app/src/main/
├── kotlin/com/banknotify/
│   ├── BankNotificationListener.kt  # Перехватчик уведомлений
│   ├── TelegramSender.kt            # Отправка в Telegram
│   ├── BankNotificationParser.kt    # Парсинг текста
│   ├── BootCompleteReceiver.kt      # Автозапуск
│   ├── db/
│   │   └── BankNotificationsDatabase.kt  # Room Database
│   └── ui/
│       ├── MainActivity.kt
│       ├── HistoryFragment.kt
│       └── SettingsFragment.kt
├── res/
│   ├── layout/
│   ├── values/
│   └── drawable/
└── AndroidManifest.xml
```

## Сборка

### Debug версия
```bash
./gradlew assembleDebug
```

### Release версия
```bash
./gradlew assembleRelease
```

### Запуск на устройстве
```bash
./gradlew installDebug
```

## Troubleshooting

### Приложение не получает уведомления

1. Проверь, включен ли "Слушатель уведомлений"
2. Проверь, что банковское приложение установлено
3. Перезагрузи телефон

### Сообщения не приходят в Telegram

1. Проверь токен и Chat ID в Настройках
2. Нажми "Тестировать отправку"
3. Проверь интернет-соединение

### Проблема с сборкой

```bash
./gradlew clean build
```

## Лицензия

MIT
