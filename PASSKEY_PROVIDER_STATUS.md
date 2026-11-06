# Passkeys в sBoard

## Реализовано

- `SBoardCredentialProviderService` — поставщик Android Credential Manager (Android 14+, API 34).
  На более старых версиях служба выключена ресурсом `credential_provider_enabled`.
- **Создание** (`onBeginCreateCredentialRequest` → `PasskeyActivity`, режим create): проверка запроса
  (`WebAuthnRegistrationPolicy`), проверка источника вызова, `excludeCredentials`, подтверждение
  пользователя (разблокировка хранилища или биометрия/код экрана), ключ ES256 (P-256),
  attestation `none`, ответ `credProps.rk = true`, флаги UP/UV/BE/BS.
- **Вход** (`onBeginGetCredentialRequest` → `PasskeyActivity`, режим get): список passkeys для `rpId`
  с учётом `allowCredentials`; при заблокированном хранилище показывается действие
  «Разблокировать sBoard» (AuthenticationAction), после разблокировки список возвращается системе.
  Подпись выполняется только после подтверждения пользователя.
- **Хранение**: passkey — обычная запись хранилища (`ItemType.PASSKEY`). Закрытый ключ PKCS#8 лежит
  только внутри зашифрованного AES-256-GCM содержимого записи и **никогда не хранится в открытом
  виде**. Поэтому passkeys синхронизируются через `sboard_backup.enc` и восстанавливаются после
  переустановки и на новом устройстве. Счётчик подписей для синхронизируемых ключей равен 0
  (как у Google Password Manager), чтобы разные устройства не конфликтовали.
- **Проверка источника** (`CallingAppOriginResolver`):
  - браузеры: `CallingAppInfo.getOrigin()` с официальным списком привилегированных браузеров
    Google (`gstatic.com/gpm-passkeys-privileged-apps/apps.json`, кэш 7 дней; офлайн-запасной
    список содержит Chrome); при подписи используется `clientDataHash` браузера;
  - приложения: Digital Asset Links (`/.well-known/assetlinks.json` сайта RP) и
    `android:apk-key-hash:` по сертификату подписи вызывающего приложения.
- Управление: просмотр и удаление passkeys в разделе «Хранилище → Passkeys».

## Ограничения Android

- Аппаратный Keystore не используется для закрытых ключей passkeys намеренно: ключи Keystore
  нельзя перенести на другое устройство. Защита обеспечивается шифрованием хранилища.
- Включить sBoard как поставщика нужно вручную: Настройки → Пароли, ключи доступа и аккаунты
  (Профиль → «Passkeys и пароли» открывает этот экран).
- Неизвестный браузер не получит passkey (fail closed), пока не попадёт в официальный список.

## Проверено

Unit-тесты движков WebAuthn (регистрация, подпись ES256, проверка RP ID/origin, Asset Links).
Требуется проверка на устройстве Android 14+ с реальными сайтами (например, webauthn.io,
passkeys.dev) и Chrome.
