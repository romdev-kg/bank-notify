# Ревью BankNotify — 16 октября 2026

Область: приём push/SMS, парсинг, очередь и доставка Telegram, прокси, восстановление слушателя,
настройки/импорт, журнал, UI, зависимости и CI. Выпуск 1.6.1 (versionCode 10).

## Исправленные ошибки

| Приоритет | Проблема | Исправление и проверка |
|---|---|---|
| Высокий | Manifest запрещал silent/ongoing: тихая банковская оплата не доходила до кода приложения | Разрешены все четыре типа уведомлений; снят disabled filter |
| Высокий | Из нескольких чисел мог выбираться остаток, часть суммы или номер; SMS и push разбирались по-разному | Общий парсер BigDecimal; тесты остатка, групп разрядов, дробей, Unicode-пробелов, локали и исходящих операций |
| Высокий | Развёрнутый текст EXTRA_BIG_TEXT игнорировался, групповые сводки могли дублировать дочерние оплаты | Выбор полного текста, fallback на EXTRA_TEXT, пропуск summary; регрессионные тесты |
| Средний | `%2B`, `%25` и `+` в URI прокси декодировались неверно | Однократное декодирование rawUserInfo; тесты специальных символов |
| Высокий | Синхронный HTTP продолжал работать после отмены worker, мог начинать следующий прокси | Отмена coroutine отменяет Call, в том числе чтение тела; перед маршрутом проверяется cancellation; тесты |
| Высокий | Системный backup переносил шифрованные prefs без ключа Keystore, а старая очередь могла отправиться повторно | Исключены prefs и база с journal/WAL/SHM из cloud/device transfer; обычное обновление APK сохраняет локальные данные |
| Средний | Поворот экрана мог прервать импорт между записью настроек и планированием фоновой работы | ViewModel, проверка отмены перед commit, завершение commit+schedule без прерывания; тесты пароля, отмены и сохранения истории/очереди |
| Средний | UI не учитывал обязательный edge-to-edge современных Android | Общие отступы от системных панелей, вырезов и клавиатуры |
| Средний | GitLab только зеркалировал код, сборка и ключ находились в GitHub; mirror использовал force-push | GitLab verify/release с артефактами и прежним ключом, GitHub manual fallback, только обычный push |

Полный сырой текст входящих уведомлений больше не пишется до проверки банка и оплаты.
Счётчики «Пропуск» остаются агрегированными. В telegram_outbox хранится отформатированная строка оплаты.
Уточнение повторного ревью: исходный текст не сохраняется, а старая таблица bank_notifications
в production больше не пополняется; прежние записи остаются в базе. См.
[повторное ревью через Claude](2026-10-16-claude-review.md).

## Принятые продуктовые решения

- Неоднозначные сообщения «перевод» отправляются как обычные поступления, без метки проверки.
- Явные покупки/списания/исходящие переводы не являются поступлением; валютную сумму нельзя подменять рублёвым остатком.
- При потере ответа Telegram повторяем доставку: возможный дубль предпочтительнее потери оплаты.
- Прокси перебираются по порядку, включая кнопку «Тест». Telegram 4xx и retry_after не требуют перебора маршрутов.
- Перенос настроек между телефонами — через зашифрованный экспорт. История и очередь остаются на исходном устройстве.

## Обновления зависимостей

Проверены stable-релизы в официальных Maven metadata, preview/beta/alpha не выбирались.

| Компонент | Было | Стало |
|---|---|---|
| org.jetbrains.kotlin:kotlin-stdlib | 2.0.21 | 2.4.21 |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.8.1 | 1.11.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-core | 1.8.1 | 1.11.0 |
| androidx.appcompat:appcompat | 1.7.0 | 1.8.0 |
| androidx.core:core-ktx | 1.13.1 | 1.19.1 |
| androidx.activity:activity-ktx | 1.9.3 | 1.13.0 |
| androidx.fragment:fragment-ktx | 1.8.5 | 1.9.1 |
| androidx.constraintlayout:constraintlayout | 2.2.0 | 2.2.2 |
| androidx.cardview:cardview | 1.0.0 | 1.0.0 |
| com.google.android.material:material | 1.12.0 | 1.14.0 |
| androidx.work:work-runtime-ktx | 2.9.1 | 2.12.0 |
| androidx.room:room-runtime | 2.6.1 | 2.8.5 |
| androidx.room:room-ktx | 2.6.1 | 2.8.5 |
| androidx.room:room-compiler | 2.6.1 | 2.8.5 |
| androidx.lifecycle:lifecycle-runtime-ktx | 2.8.7 | 2.11.0 |
| androidx.lifecycle:lifecycle-viewmodel-ktx | 2.8.7 | 2.11.0 |
| androidx.lifecycle:lifecycle-livedata-ktx | 2.8.7 | 2.11.0 |
| com.squareup.okhttp3:okhttp | 4.12.0 | 5.5.0 |
| androidx.security:security-crypto | 1.1.0-alpha06 | 1.1.0 |
| com.google.code.gson:gson | 2.11.0 | 2.14.0 |
| junit:junit | 4.13.2 | 4.13.2 |
| org.jetbrains.kotlinx:kotlinx-coroutines-test | 1.8.1 | 1.11.0 |
| org.robolectric:robolectric | 4.15.1 | 4.17 |
| androidx.test:core | 1.6.1 | 1.7.0 |
| androidx.test.ext:junit | 1.2.1 | 1.3.0 |
| androidx.test.espresso:espresso-core | 3.6.1 | 3.7.0 |

Инструменты: AGP 8.2.0 → 9.4.1, KSP → 2.3.12, Gradle 8.5 → 9.7.0, JDK 17 → 25 LTS, compile SDK 35 → 37.2, target 35 → 37.
Последний stable Gradle — 9.8.1; выбран 9.7.0 как верхняя полностью поддерживаемая Kotlin 2.4.21 версия. JVM target оставлен 17.
AGP 9 использует встроенный Kotlin; версия KGP переопределена через buildscript classpath.
Совместимость фактической комбинации проверяется сборкой: AGP 9.4 новее максимума AGP 9.3.1 в таблице тестирования Kotlin.

Источники версий:

- [kotlin: Maven metadata](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml)
- [coroutines: Maven metadata](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml)
- [ksp: Maven metadata](https://repo.maven.apache.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml)
- [agp: Maven metadata](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml)
- [okhttp: Maven metadata](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml)
- [gson: Maven metadata](https://repo.maven.apache.org/maven2/com/google/code/gson/gson/maven-metadata.xml)
- [robolectric: Maven metadata](https://repo.maven.apache.org/maven2/org/robolectric/robolectric/maven-metadata.xml)
- [appcompat: Maven metadata](https://dl.google.com/dl/android/maven2/androidx/appcompat/appcompat/maven-metadata.xml)
- [core: Maven metadata](https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/maven-metadata.xml)
- [activity: Maven metadata](https://dl.google.com/dl/android/maven2/androidx/activity/activity-ktx/maven-metadata.xml)
- [fragment: Maven metadata](https://dl.google.com/dl/android/maven2/androidx/fragment/fragment-ktx/maven-metadata.xml)
- [constraintlayout: Maven metadata](https://dl.google.com/dl/android/maven2/androidx/constraintlayout/constraintlayout/maven-metadata.xml)
- [cardview: Maven metadata](https://dl.google.com/dl/android/maven2/androidx/cardview/cardview/maven-metadata.xml)
- [work: Maven metadata](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime-ktx/maven-metadata.xml)
- [room: Maven metadata](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/maven-metadata.xml)
- [lifecycle: Maven metadata](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-ktx/maven-metadata.xml)
- [security: Maven metadata](https://dl.google.com/dl/android/maven2/androidx/security/security-crypto/maven-metadata.xml)
- [testcore: Maven metadata](https://dl.google.com/dl/android/maven2/androidx/test/core/maven-metadata.xml)
- [testjunit: Maven metadata](https://dl.google.com/dl/android/maven2/androidx/test/ext/junit/maven-metadata.xml)
- [espresso: Maven metadata](https://dl.google.com/dl/android/maven2/androidx/test/espresso/espresso-core/maven-metadata.xml)
- [material: Maven metadata](https://dl.google.com/dl/android/maven2/com/google/android/material/material/maven-metadata.xml)

- [Совместимость Kotlin/Gradle/AGP](https://kotlinlang.org/docs/gradle-configure-project.html)
- [AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
- [Встроенный Kotlin в AGP](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Официальный каталог Android SDK](https://dl.google.com/android/repository/repository2-3.xml)

## Оставшиеся ограничения и улучшения

- Доставка имеет семантику «не менее одного раза»: Telegram sendMessage не предоставляет ключ идемпотентности. Потеря ответа после принятия сообщения может дать дубль. Push/SMS одной оплаты не объединяются; новая версия уведомления с другим postTime также может быть отдельным событием.
- Android/OEM может задерживать WorkManager, запрещать фоновую работу после Force stop или не передать уведомление. Удалённое из шторки до приёма сообщение восстановить невозможно. Нужна проверка на используемом телефоне с его настройками батареи.
- Security Crypto 1.1.0 — последний stable, но API deprecated. Существующее хранилище сохранено ради безопасного обновления; переход на собственную схему Keystore требует отдельной миграции и проверки восстановления.
- Broad keep-правила ProGuard пока сохранены. Их сужение может уменьшить APK, но требует smoke-тестов минифицированной версии на устройстве (Room, WorkManager, Gson, импорт и UI).
- Есть оставшиеся lint warnings: главным образом строки вне resources, локализация, autofill, неиспользуемые ресурсы, contentDescription и оптимизация UI. Это не блокирует сборку; полного редизайна и локализации в этом выпуске нет.
- Старые HistoryFragment/SettingsFragment не используются основной Activity. Их удаление или приведение к одному lifecycle лучше выполнять отдельным небольшим изменением после подтверждения навигации.
- История оплат не имеет срока хранения; очередь нельзя чистить до доставки. Для долгой эксплуатации можно отдельно добавить настройку срока хранения доставленных записей.
- Эмулятор/физический телефон не использовался в финальной проверке этого выпуска; unit/Robolectric и lint не подтверждают поведение конкретной прошивки.

## Проверка

Регрессионные тесты воспроизвели ошибки до исправлений. Проверяются общий парсер,
выбор текста уведомления, SMS, порядок прокси, отмена HTTP, import cancellation,
сохранение Room history/outbox и backup exclusions. Полный результат тестов публикуется
в GitLab JUnit report; lint HTML и debug APK — в verify, signed APK и R8 mapping — в release-apk.

Локальная сборка: JDK 25, SDK 37.2; команды `testDebugUnitTest assembleDebug lintDebug assembleRelease`.
Конфигурация GitLab проверена CI Lint API. Keystore передан из GitHub в GitLab в одноразовом
зашифрованном контейнере; ключ/пароли не включены в исходники, артефакты APK или логи сборки.

Сертификат release 1.6.1 сравнен с опубликованным 1.6.0: SHA-256 `d0b095e3ac0b129d08201b886db007e56d89fb8f4e5eac548bb59edfaeb2f333` совпадает.

Итог локальной проверки: **52 теста, 0 ошибок/падений**, debug APK, lintDebug и подписанный
minified release APK успешно собраны. Lint: 0 errors, 86 warnings (перечислены выше).
