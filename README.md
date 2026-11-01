# sBoard

**Status: ранний этап разработки.** Репозиторий содержит Android-приложение на Kotlin и Jetpack Compose. Реализованные части перечислены ниже; наличие экрана или настройки не считается подтверждением готовности функции.

## Что уже есть

- Одно Android-приложение с разделами главного экрана, клавиатуры, хранилища, инструментов и профиля.
- Системная клавиатура с русским и английским вводом, символами, цифрами, эмодзи, вставкой из буфера обмена, округлыми клавишами, темами и вибрацией. Голосовой ввод использует Android SpeechRecognizer: офлайн на поддерживаемых устройствах, сетевой системный распознаватель — только после отдельного согласия.
- Локальное хранилище с зашифрованными записями, мастер-паролем, биометрической разблокировкой и Recovery Key.
- Служба Autofill для поддерживаемых учётных записей, генератор паролей, TOTP и зашифрованное резервирование через Google Drive.
- Локализованный интерфейс RU/EN.

## Ограничения

Автокоррекция, предиктивный ввод, T9, пользовательский словарь, история буфера обмена, полноценная интеграция WebAuthn/passkey и бесшовная синхронизация изменений между устройствами не завершены. Google Drive использует зашифрованные резервные копии; это ещё не гарантирует непрерывное разрешение конфликтов и двустороннюю синхронизацию. Биометрия, Autofill и IME требуют проверки на реальном устройстве. См. `BUILD_STATUS.md`, `PASSKEY_PROVIDER_STATUS.md` и `SYNC_STATUS.md`.

Приложение не содержит отдельного AI-помощника. Не вводите реальные секреты в тестовую сборку до самостоятельной проверки безопасности и восстановления резервных копий.

## Сборка

Требуются JDK 17, Android SDK Platform 36 и Build Tools 36.0.0. Используйте Gradle Wrapper из репозитория:

```sh
./gradlew testDebugUnitTest :app:assembleDebug
```

APK для отладки появится в `app/build/outputs/apk/debug/`. На Windows используйте `gradlew.bat`. GitHub Actions выполняет модульные тесты и собирает debug APK при push и pull request в `main`. Для Google Drive OAuth необходима отдельная настройка Google Cloud/Android OAuth-клиента и соответствующей конфигурации приложения; секреты и ключи подписи не должны попадать в Git. Подробности: `GOOGLE_DRIVE_SETUP.md`.

## English

**Status: early development.** This repository contains an Android application built with Kotlin and Jetpack Compose. A visible screen or setting does not imply that the related feature is complete.

### Implemented foundations

- One Android app with Home, Keyboard, Vault, Tools, and Profile sections.
- A system keyboard with Russian and English input, symbols, digits, emoji, clipboard paste, rounded keys, themes, and haptics. Voice typing uses Android SpeechRecognizer: on-device where available; network-backed system recognition requires separate consent.
- A local vault with encrypted records, a master password, biometric unlock, and a Recovery Key.
- An Autofill service for supported credentials, a password generator, TOTP, and encrypted Google Drive backups.
- Russian and English app localization.

### Limitations

Autocorrection, predictive typing, T9, a user dictionary, clipboard history, complete WebAuthn/passkey integration, and seamless cross-device change synchronization are unfinished. Google Drive currently provides encrypted backups; this does not yet guarantee continuous conflict resolution or bidirectional synchronization. IME, Autofill, and biometrics still require real-device verification. See `BUILD_STATUS.md`, `PASSKEY_PROVIDER_STATUS.md`, and `SYNC_STATUS.md`.

The app has no separate AI assistant. Do not enter real secrets into a development build before independently reviewing security and backup recovery.

### Build

Requires JDK 17, Android SDK Platform 36, and Build Tools 36.0.0. Run:

```sh
./gradlew testDebugUnitTest :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. Use `gradlew.bat` on Windows. GitHub Actions runs unit tests and builds the debug APK on pushes and pull requests to `main`. Google Drive OAuth requires a configured Google Cloud/Android OAuth client and app configuration. Never commit credentials or signing keys. See `GOOGLE_DRIVE_SETUP.md`.
