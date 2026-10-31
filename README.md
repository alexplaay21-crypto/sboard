# sBoard — Android foundation (0.1.0)

Новый самостоятельный Android-проект. Он не связан с репозиторием Shortcut (автоматизация команд).

## Что уже реализовано
- Kotlin + Jetpack Compose + Material 3.
- Нижняя навигация из пяти вкладок.
- Сохранение выбранной вкладки при пересоздании Activity (`rememberSaveable`).
- Базовая тёмная дизайн-система sBoard.
- Экраны Главная / Клавиатура / Хранилище / Инструменты / Профиль.
- Генератор паролей на `SecureRandom` с гарантированным включением выбранных групп символов.
- Локальный Vault на Room: создание, просмотр, редактирование, удаление и поиск записей.
- Зашифрованный payload записей с помощью AES-256-GCM; AAD привязан к ID записи.
- Мастер-пароль с Argon2id (64 MiB, 3 прохода) оборачивает случайный 256-битный ключ Vault.
- Конфигурация ключа хранится в Room; миграция v1 → v2 переносит существующие записи со старого device-bound ключа на новый ключ Vault.
- Ключ Vault хранится в памяти только пока хранилище разблокировано; блокировка выполняется вручную, при уходе приложения в фон и при закрытии экрана Vault.
- Recovery Key: 256 бит случайной энтропии, Argon2id + AES-256-GCM envelope; ключ показывается пользователю один раз и не хранится в открытом виде.
- Биометрическая разблокировка через AndroidX BiometricPrompt с CryptoObject и AES-256 ключом Android Keystore, требующим сильную биометрию для каждой операции.
- Возможность сменить мастер-пароль без перешифрования записей: переоборачивается только Vault Key.
- Локальный зашифрованный backup базы: Argon2id от Recovery Key, независимые AES-256-GCM теги для каждого блока, проверка структуры и SQLite-заголовка перед восстановлением.
- Экран резервного копирования в профиле: экспорт `.enc` через системный диалог сохранения и восстановление через системный выбор файла.
- Копирование пароля по явному нажатию с пометкой чувствительного содержимого системного Clipboard.
- Предварительный просмотр клавиатуры и локальные переключатели интерфейса.

## Языки приложения (новый этап)
- Интерфейс поддерживает русский и английский языки.
- В профиле доступен выбор «Как на устройстве», «Русский» и «Английский».
- Выбор сохраняется локально и применяется ко всем основным экранам, Vault, настройкам, резервному копированию и системным сообщениям приложения.
- В режиме «Как на устройстве» используется язык Android: русский для `ru`, английский для остальных языков.
- Предпросмотр раскладки клавиатуры переключается между RU и EN; фактическая системная IME пока не реализована.

## Google Drive (новый этап)
- Добавлен `GoogleDriveBackupClient` для Google Drive API v3.
- Ищет `sboard_backup.enc` в `appDataFolder`, обновляет найденный файл или создаёт его.
- Использует resumable upload блоками по 8 MiB и поддерживает скачивание файла.
- В экране резервного копирования добавлен интерактивный запрос доступа Google Identity с минимальным scope `drive.appdata`.
- При нажатии «Загрузить ... в Google Drive» сначала создаётся локальная зашифрованная копия, затем пользователь выдаёт доступ, после чего в Drive передаётся только `.enc` файл.
- Access token используется только в оперативной памяти на время запроса и не записывается в Room, DataStore или WorkManager.
- Настройка OAuth Android client и SHA-1 подписи описана в `GOOGLE_DRIVE_SETUP.md`.
- Автоматическое фоновое резервное копирование пока не включено: оно требует отдельного безопасного механизма доступа к ключу backup при заблокированном Vault.

## Что пока намеренно не реализовано
Это ранняя разработка, не прошедшая полную Android-сборку и аудит безопасности. Мастер-пароль, Recovery Key и биометрические API добавлены в исходники, но ещё требуют проверки на реальных устройствах. Защищённого резервного копирования и переноса базы пока нет. Не используйте приложение пока как единственное хранилище критически важных секретов.
- Проверка поведения Keystore/биометрии на реальных устройствах разных производителей и API levels.
- Полная модель автоматической блокировки по настраиваемому таймеру.
- Полный аудит обработки секретов в Compose UI и временных строк в памяти.
- Подтверждение пользователем сохранения Recovery Key и UX-проверка восстановления.
- Настоящая IME-служба клавиатуры.
- Autofill, Credential Provider / Passkeys, OTP и полноценная история Clipboard.
- Проверка Google OAuth на физическом устройстве с настроенным Android OAuth client; автоматическое фоновое резервное копирование.
- Backend, аккаунты и синхронизация.
- AI и офлайн-перевод.

## Сборка
Откройте папку в Android Studio (JDK 17), выполните Gradle Sync и запустите конфигурацию `app`.
SDK 36 должен быть установлен через SDK Manager.

## Локализация — ограничения
Перевод реализован централизованным словарём `SBoardI18n` в Compose-слоях и сообщениях сервисов. Перед выпуском нужно провести редакторскую вычитку всех английских формулировок и вынести весь словарь в Android `strings.xml` для полноценной интеграции с инструментами перевода.

## Архитектурный следующий шаг
Следующий шаг: провести Gradle Sync/сборку на Android SDK 36, проверить миграции Room, протестировать локальный backup/restore и Recovery Key на физическом устройстве, затем подключить Google OAuth и Drive `appDataFolder`. Recovery Key восстанавливает Vault Key, но для переноса на другой телефон также потребуется зашифрованная копия базы/backup; биометрический Keystore-ключ на новое устройство не переносится и должен быть создан заново.


## Stage 21 — System keyboard foundation

- Added `SBoardInputMethodService`, registered as an Android IME service.
- Added RU/EN layouts, Shift, Backspace, Space, Enter, and a basic symbols page.
- Added a basic numeric keypad for numeric input fields.
- Added a private-input label for password fields; this IME does not store typed text or learn from input.
- Added buttons in the Keyboard tab to open Android input-method settings and the system keyboard picker.
- App UI language and keyboard layout are independent.

This is a functional first IME foundation, not a production keyboard yet. It does not provide predictive suggestions, autocorrect, glide typing, personalized dictionary, password/OTP suggestions, Autofill, or Passkeys. Password fields suppress any suggestion behavior because no suggestion engine is implemented. Device testing and Gradle compilation are still required.

## OTP / TOTP entries

Create a Vault entry with category **Код 2FA**. Put the account's Base32 TOTP secret in **Пароль / секрет**, and set **Сайт / адрес** to the exact website host (for example `example.com`). The secret remains inside the encrypted Vault record.

Default parameters are SHA-1, 6 digits, and a 30-second period. To override them, add a separate line to Notes:

`sboard-otp: digits=6;period=30;algorithm=SHA1`

Supported algorithms: `SHA1`, `SHA256`, `SHA512`; digits: 6–8; period: 15–120 seconds. Android Autofill can offer a current code when the target app or website exposes a recognizable one-time-code field. It does not guarantee support for custom web forms.

## Passkeys development foundation

This source snapshot adds encrypted Passkey storage (Room migration 3 -> 4), RP-bound AES-GCM wrapping of private-key bytes, and repository operations. See `PASSKEY_PROVIDER_STATUS.md` for the exact boundary: the Android Credential Provider / WebAuthn ceremony is not implemented yet, so Passkeys are not yet available for real sign-in.


## Android Credential Provider (development stage)

The project registers sBoard as a public-key credential provider on Android 14+ and includes development-stage Passkey discovery, WebAuthn engines, trusted-origin foundations, and a first-pass BiometricPrompt/assertion response path. Locked-Vault discovery uses encrypted metadata plus a device-bound HMAC RP index. This remains source-level development integration: the full Gradle build, Room migrations, and Android device flow have not been verified. Passkey registration is not yet connected to the Android provider. See `PASSKEY_PROVIDER_STATUS.md`.

### Passkey development status

The project now includes a standalone WebAuthn assertion core (`WebAuthnAssertionEngine`) for ES256/RS256 assertions, including clientDataJSON, authenticatorData, signature counters, and response JSON serialization. It is intentionally not connected to the system provider flow yet: trusted origin verification, biometric gating, and final Credential Manager response handling must be completed first. See `PASSKEY_PROVIDER_STATUS.md`.

### Passkey development status

The project now includes standalone WebAuthn assertion (`get`) and registration (`create`) core engines for ES256. These are development components, not an end-to-end Credential Manager provider yet. Review `PASSKEY_PROVIDER_STATUS.md` and `BUILD_STATUS.md` before attempting device integration; no successful build or device test is claimed.


### Passkey security hardening

The standalone WebAuthn registration and assertion engines share a conservative HTTPS origin/RP-ID matching policy. It rejects unrelated and lookalike domains. This check does not replace Public Suffix List validation or Android Digital Asset Links verification; the Credential Manager integration must perform those checks before enabling ceremonies.

### Passkey biometric gate

Private Passkey material is guarded by a short-lived, single-use authorization object tied to the selected credential. The selection activity now presents the Android Keystore-backed `BiometricPrompt` with the actual authenticated cipher and only then issues the grant. This first integration pass still requires Android device testing.

### Passkey calling-app identity (development)

The project now includes a conservative `CallingAppOriginResolver` foundation. Direct Android app origins are formatted from the calling package signing certificate. Browser-delegated origins require a reviewed privileged-caller allowlist; the current allowlist is empty, so delegated browser requests fail closed. The resolver is now called by the selection/signing flow for direct Android callers. Browser-delegated origins remain disabled because the privileged-caller allowlist is empty; Related Origin Requests are not implemented.

### Passkey trust verification

The current development branch includes a Digital Asset Links verifier for native Android RP associations. Native Android origins are returned by the resolver only after the RP's `assetlinks.json` matches the calling package and signing certificate. This verifier is called by the trusted-origin resolver used in the selection activity. Network and device behavior still require testing; see `PASSKEY_PROVIDER_STATUS.md` for limitations.

### Passkey integration status

The project now connects AndroidX `CallingAppInfo` resolution, Digital Asset Links verification,
Android Keystore-backed BiometricPrompt authorization, the one-shot assertion pipeline, and the
`PublicKeyCredential` response hand-off in `PasskeySelectionActivity`. This is not yet a verified
end-to-end feature: Gradle compilation and Android 14+ device tests remain outstanding. The Vault
can remain locked during provider discovery; browser-delegated `clientDataHash` requests are
rejected, and registration is not integrated.

### Passkey discovery while Vault is locked

sBoard's Credential Manager provider can now show matching saved Passkey entries after an app/process restart without first unlocking the Vault. Discovery reads only a narrow metadata projection (site/account labels and credential ID); it does not load private-key ciphertext or key material. Selecting an entry still requires the system biometric prompt before the Vault key is unwrapped and the private key is used. Discovery metadata (including RP ID and credential ID) is currently plaintext in the local Room database; only the private key is encrypted. Full Android build and device validation are still pending.


### Passkey metadata privacy (schema v5)

Passkey site/account labels, RP ID, user handle, and COSE public key are stored in an AES-GCM envelope protected by a separate Android Keystore key. A keyed HMAC index permits RP-specific discovery while the Vault is locked. The WebAuthn credential ID and usage timestamps remain visible in SQLite for Credential Manager matching and sorting. Private-key bytes remain encrypted separately under the Vault key. Legacy records are converted lazily when accessed. This is protection against offline database extraction, not against malicious code running under the app UID.

### Passkey storage integrity

The development Passkey repository includes a read-only integrity audit. It reports legacy rows,
missing or orphaned metadata envelopes, authentication/index failures, invalid credential material,
and Android Keystore key availability. It never automatically deletes records or resets Keystore
keys. Damaged records are excluded from use; restore only from a backup format that can safely
rehydrate the metadata, or re-register the affected Passkey. See `PASSKEY_PROVIDER_STATUS.md`.


### Backup restore safety

Restore now authenticates every encrypted chunk before touching the active Vault, then performs a read-only SQLite `quick_check` and checks the Room schema version against the migration versions supported by this build. The live database is staged for rollback before atomic replacement. This path still needs Android instrumentation tests, including process interruption and storage failure scenarios.

## Google Drive backup version history

- New uploads create immutable versioned files in `appDataFolder` instead of overwriting the previous snapshot.
- The Backup screen can list versioned snapshots and restore a selected one using the Recovery Key.
- The Drive API list is paginated and bounded to 1,000 returned snapshots per request flow.
- Downloaded snapshots are checked against Drive's MD5 transport checksum when the API provides it, then still pass the local authenticated-decryption and SQLite restore preflight.
- Retention policy can preview which older versions would be candidates for manual cleanup, but this stage never deletes backups automatically.
- The legacy `sboard_backup.enc` file is preserved for backwards compatibility and is not automatically migrated into the version history.
- OAuth, network behavior, Compose compilation, and restore on a physical device remain unverified; no APK was produced.


## Automatic backup scheduling (foundation)

- The initial scheduler supported periodic and important-change requests. The latest implementation replaces the daily schedule in the settings UI with Room event-triggered work after committed changes.
- OAuth access tokens, Recovery Keys and Vault keys are never placed in WorkManager input data.
- The worker requires an explicitly registered `BackupAutomationRuntime.executor`. If no secure executor is registered, it retries without reading or uploading any data. This is deliberate: background backup is not considered active until the app wires a secure token provider and backup-key flow.
- The current UI does not yet expose schedule controls, and no automatic cleanup/retention deletion is performed.
- WorkManager scheduling is best-effort and subject to Android battery/background execution policies; periodic work is not an exact clock alarm.

### Backup automation execution engine

The project includes `DriveBackupAutomationExecutor`, which joins the encrypted backup manager to immutable Google Drive version uploads. The latest implementation installs the executor from `SBoardApplication`, obtains a short-lived Drive token silently through Google Identity after explicit user consent, and uses an explicitly opted-in device-bound Recovery Key copy. Neither credential is placed in WorkManager input or sBoard preferences. See the latest event-driven backup section below for current limitations and verification status.

### Device-bound background backup credentials

Scheduled backup can optionally use a Recovery Key copy wrapped by a non-exportable AES-256-GCM Android Keystore key. The user must explicitly opt in by saving the Recovery Key in backup settings. OAuth access tokens are not persisted by this component; an app-provided silent token source is required. A worker never displays authorization UI and never unlocks the Vault. The Room database stores Vault payloads encrypted at rest, and the complete snapshot is encrypted again before upload. Keep the offline Recovery Key separately because the device-bound copy cannot survive device loss, uninstall, cleared app data, or Keystore invalidation.

### Backup & Sync settings UI

- Added a bilingual Backup & Sync settings screen in Profile.
- The screen stores only non-secret preferences: desired automation state, interval (12–168 hours), and retention preference (1–100 versions).
- Users can explicitly opt in to saving a device-bound encrypted Recovery Key copy; the UI explains that this is not a replacement for an offline Recovery Key and may be unavailable after device loss or reinstall.
- The Recovery Key is entered in a non-saveable dialog and passed directly to `DeviceBoundBackupKeyStore`; it is not written to settings or WorkManager input.
- The UI deliberately reports background automation as not active until a secure executor and silent Drive OAuth integration are installed. The retention preference is not yet connected to automatic deletion.

### WebAuthn registration request hardening

The registration core now validates the supported creation-request subset through `WebAuthnRegistrationPolicy` before generating keys. It rejects malformed challenges, invalid RP/origin pairs, unsupported algorithms/extensions, incompatible authenticator-selection requirements, and malformed exclusion lists. Provider-side `create()` ceremony wiring remains a separate pending task; see `BUILD_STATUS.md` and `PASSKEY_PROVIDER_STATUS.md`.

### Event-driven encrypted backup and Google OAuth (development increment)

The backup settings now use an event-driven model rather than a daily timer. Room invalidations for Vault and Passkey data trigger a WorkManager upload after a short 3-second debounce; rapid edits are combined, and the task waits for a network connection. The application requests a short-lived `drive.appdata` token from Google Identity at worker runtime. sBoard stores only whether the user completed the consent flow; it does not persist OAuth tokens. Background uploads require the user to explicitly opt in to a device-bound Recovery Key copy. The Room payloads are already encrypted at rest and the complete database snapshot is encrypted again before upload.

This is one-way encrypted backup, not multi-device bidirectional synchronization. OAuth revoke/disconnect, automatic retention deletion, conflict resolution, user-visible retry/error state, and full device verification remain pending. See the latest entry in `BUILD_STATUS.md` and `GOOGLE_DRIVE_SETUP.md`.

### Backup status and manual snapshot (2026-10-31)

Backup Settings now records the last attempt and last successful backup, shows whether the most recent run succeeded, needs attention, or failed, and keeps a short sanitized diagnostic message. A manual “Back up now” action can queue a snapshot even when automatic change-triggered backups are disabled. This status data contains no OAuth token, Recovery Key, or Vault record content. The screen refreshes status on demand; live progress observation and a full retry/history timeline are not yet implemented.


## Upload integrity verification (latest development increment)

Versioned Google Drive uploads now calculate the encrypted local file size and MD5 before upload, then fail closed unless Drive's completed-upload metadata reports the same size and a matching 32-character MD5 checksum. This prevents the backup worker from recording success when the completed upload metadata is missing or inconsistent. This checksum is a transport-integrity check only; authenticity and tamper detection remain provided by the authenticated encrypted backup format (AES-GCM). Five pure policy tests cover matching metadata, missing checksum, size mismatch, checksum mismatch, and malformed checksum.

The Android Gradle build and device-level Drive upload have not been run in this environment; the integration still requires a real Google OAuth grant and Android verification.


## Explicit backup-version deletion (latest increment)

- Added an explicit delete action for a selected encrypted Google Drive snapshot.
- The UI asks for confirmation before deletion.
- The Drive client re-lists versions before deleting and refuses to delete the last visible recovery point.
- After deletion, the history is refreshed. No automatic retention deletion is enabled.
- Added a unit test for the last-backup deletion guard.
- Android Gradle build, JUnit suite and live Drive API behavior remain unverified in this environment.

### Explicit restore from Google Drive
The development source now includes `DriveBackupRestoreCoordinator`. It lists versioned snapshots, revalidates the selected Drive file before download, downloads to a temporary encrypted cache file, and invokes the existing authenticated restore pipeline. Restore is always explicit and requires the Recovery Key; Google OAuth access tokens and Recovery Keys are not persisted by this coordinator. A dedicated onboarding/restore UI and real-device end-to-end verification remain pending.


## 2026-10-31 — first-run Drive restore screen

- Added a separate Profile entry for restoring onto a replacement/new device; it is distinct from ordinary backup management.
- The screen requests the narrow `drive.appdata` OAuth scope, lists versioned sBoard snapshots, shows timestamp/size metadata, and keeps the access token only in transient Compose memory. No token or Recovery Key is persisted by this screen.
- The user must select a snapshot, enter the 43-character Recovery Key, and confirm database replacement. The restore coordinator re-lists Drive metadata, downloads to a temporary encrypted file, verifies transport metadata where available, authenticates/decrypts the backup, validates SQLite integrity/schema, and replaces the local database with rollback protection.
- After restore, the UI directs the user to the Vault tab to unlock with the original master password stored inside the restored database.
- Russian and English UI strings were added.
- Not verified: full Gradle/Compose build, Android OAuth consent, live Drive listing/download, restore on a clean install, and physical-device testing. The screen is source-level implementation only; no APK is produced.

### First-run security setup

New installations now open a guided setup before the main tabs. The wizard creates the local master-password envelope, generates a 43-character Recovery Key, displays it only in memory, supports sensitive clipboard copying, and requires the user to acknowledge that the key has been stored separately. If setup is interrupted after Vault creation, the next launch asks for the master password and issues a fresh Recovery Key. Existing installations with a master password are not forced to rotate their Recovery Key. Google Drive connection remains available in Profile after local setup; restore can also be launched from the first-run screen.


## Google Drive disconnect and OAuth revocation (latest development increment)

The backup settings screen now offers an explicit Google Drive disconnect action. After confirmation, sBoard calls Google Identity `AuthorizationClient.revokeAccess()` for the app's requested `drive.appdata` scope. On success, the app clears its local consent flag, disables event-triggered backup scheduling, and leaves the local Vault, device-bound Recovery Key, and already uploaded Drive backup files untouched. If revocation fails, the UI keeps the previous connected state and reports the failure. Google documents that `revokeAccess()` clears cached tokens and revokes all scopes previously granted to this app; sBoard currently requests only `drive.appdata`.

This source change has not been validated by a full Android Gradle build or on-device OAuth test.

### Multi-device synchronization groundwork

The current development branch includes a local non-secret change journal for Vault record create/update/delete operations and a conservative conflict policy. It is preparation for future multi-device synchronization, not yet a live sync feature. Current Google Drive behavior remains encrypted backup snapshots. Cross-device edits with different content are treated as conflicts rather than silently applying last-write-wins.

### Encrypted multi-device sync transport groundwork

A versioned AES-256-GCM envelope now packages one Vault journal event without exposing record fields or journal metadata in plaintext. UPSERT events carry the already encrypted Vault row; DELETE events carry a tombstone. This is only a cryptographic transport format: it is not yet connected to Google Drive and does not yet provide remote event feeds, replay protection, acknowledgements, or conflict resolution UI. See `SYNC_STATUS.md` for scope and verification limits.

## Google Drive multi-device sync transport foundation
- Added a Drive API v3 transport for encrypted per-change envelopes stored in the app-specific `appDataFolder` (separate from whole-database backup snapshots).
- Supports listing remote change files, uploading one encrypted envelope with a stable idempotent filename, and downloading a selected envelope.
- Re-uploading an existing change ID verifies the existing bytes and fails closed if they differ; it never silently overwrites a different event.
- Uses the existing short-lived OAuth token as an in-memory call argument; the transport does not persist it.
- Important: `appDataFolder` is hidden from the normal Drive UI and is only accessible to the same app. The same Google account on another device can access it through the same sBoard app identity after OAuth consent. This is not yet full bidirectional sync; journal-to-envelope creation, remote Room application, acknowledgements/replay protection, conflict UI and automatic scheduling remain to be integrated.
- See `GOOGLE_DRIVE_MULTI_DEVICE_SYNC.md` for the intended behavior and security boundaries.


### Multi-device sync exchange foundation

The latest development increment adds a Google Drive exchange coordinator. It uploads the latest pending encrypted change per Vault record and downloads authenticated remote envelopes into an encrypted local inbox. Incoming events are staged for explicit conflict handling; they are not automatically applied yet. See `SYNC_STATUS.md` for limitations and verification status.

Outgoing encrypted envelopes are cached in the local journal before upload so retries after process death reuse identical ciphertext and remain idempotent.

### Multi-device sync inbox resolution (development)

Incoming per-record changes downloaded from Google Drive remain encrypted in the local sync inbox until the user explicitly chooses the remote or local version. `VaultSyncInboxManager` validates both the outer authenticated sync envelope and the inner AES-GCM Vault record before applying a remote value. Resolution is transactional and emits a fresh local journal event so the selected state can be propagated to other devices. The review UI, automatic event-driven sync scheduling, and full end-to-end Drive verification are still pending.

### Event-driven Google Drive sync scheduling

Local journal commits now enqueue a debounced one-time sync pass. Returning to the app or unlocking the Vault requests an immediate catch-up. The worker obtains Drive authorization silently at run time and requires the in-memory Vault session to be unlocked; secrets are not placed in WorkManager data. Incoming remote changes are still staged for explicit review rather than silently applied. See `SYNC_STATUS.md` for current limitations.


## Offline-safe synchronization scheduling (development)
- Local journal changes enqueue a unique WorkManager sync request even without network connectivity.
- WorkManager waits for `NetworkType.CONNECTED`, then the worker attempts the encrypted Google Drive exchange.
- A debounce window coalesces rapid edits. Secrets are acquired at execution time and are not serialized into WorkManager data.
- If authentication or Vault unlock is unavailable when work runs, the attempt exits safely; opening the app/Vault or making another local change schedules a new attempt.
- This behavior is source-level only until Android build and physical-device tests pass.


### Replacement-device sync replay protection (2026-10-31)
After restoring a whole-database snapshot on a new installation, the per-record sync coordinator compares authenticated remote change IDs with the restored local journal. Historical changes already contained in the snapshot are skipped rather than added to the incoming review inbox. This avoids replaying the original device's old history just because the replacement installation has a new device ID. This source-level behavior still needs full Android/JUnit and two-device verification.

### Restore schema compatibility

The Room schema version is defined once as `VAULT_DATABASE_SCHEMA_VERSION` and shared with the encrypted-backup restore validator. The validator accepts schema versions covered by the app's migration chain (currently 1 through 7) and rejects future versions until the app includes their migrations. This prevents the restore validator from accidentally lagging behind the live Room database version. Unit-test source is updated; a full Android build and restore on a device are still required before release.

### Restore safety: SQLite WAL checkpoint

Before replacing an existing Vault, restore now requires a complete `PRAGMA wal_checkpoint(TRUNCATE)` result. If SQLite reports a busy or partial checkpoint, restore aborts before locking the Vault or replacing its database. This prevents the rollback snapshot from omitting committed pages that were still in the WAL. The JVM policy tests are added but still require execution in a configured Android/Gradle build environment.

### Crash-safe database restore (development)

Database replacement now uses a durable transaction marker beside the Room database. Before the new file is atomically installed, sBoard fsyncs a rollback copy and a small marker containing the previous-database flag, rollback filename and SHA-256 digest. On the next application start, `SBoardApplication` resolves any interrupted restore before any Room database is opened: it validates and reinstalls the prior database, or removes an incomplete first-time restore when no prior Vault existed. The newly installed database is validated before the marker is cleared. If recovery material is missing or inconsistent, startup fails closed rather than opening an uncertain Vault. This is source-level protection; process-kill and real device power-loss testing is still required.

### Restore preflight validation (development)

Local-file restore and Google Drive restore share one side-effect-free preflight policy for Recovery Key format and encrypted backup size. Invalid inputs are rejected before database replacement or Drive download. Boundary tests are included; run the Android Gradle test suite in a configured Android build environment before release.

### Versioned Drive restore metadata checks (development)

Before downloading a versioned Google Drive snapshot, sBoard now requires a positive bounded file size and a syntactically valid MD5 checksum from Drive metadata. The downloaded bytes are still compared against those metadata values, and the encrypted backup's AES-GCM authentication remains mandatory during decryption. Missing or malformed Drive metadata fails closed before download. This metadata check is an early integrity/transfer guard, not a replacement for AES-GCM authentication.


## Legacy backup compatibility for device restore

The new-device restore list includes both immutable versioned snapshots (`sboard_backup_v1_...`) and the canonical legacy backup (`sboard_backup.enc`). The legacy entry is labeled in the UI and remains restore-only; deletion continues to target versioned snapshots. All selected downloads still require trusted Drive size and MD5 metadata, and the restore coordinator rechecks the selected ID/name against a fresh listing before download.

### Restore discovery preflight (development)

After Google Drive authorization, sBoard automatically lists available restore points. The discovery stage now filters out entries whose Drive size or MD5 metadata is missing or invalid, using the same policy as the download stage. The restore screen reports how many entries were skipped instead of presenting unverifiable files as selectable backups. Legacy `sboard_backup.enc` remains eligible when its metadata passes validation. This is source-level implementation; real Drive account and Android device testing is still required.


## Restore completion and Room rebind

A restore is not considered committed merely because the encrypted file decrypted or SQLite `quick_check` passed. Before the transaction marker is removed, sBoard opens the candidate through Room (including Room schema validation) and verifies that the master-password configuration envelope exists. If this check fails, Room is closed and the rollback transaction is recovered. After commit, the database invalidation observer is rebound to the new Room singleton. The Vault session remains locked and is unlocked by the user with the restored Vault's existing master password.

### Restore content integrity preflight (development)

Before an imported database can replace the current Vault, restore validation now checks schema-specific table/column presence and structural invariants for Vault records, master-password configuration, recovery and biometric envelopes, Passkeys, secure Passkey metadata, the sync journal, and the sync inbox. It also records item counts for the main data groups. This is a structural preflight: encrypted payloads remain encrypted and their AES-GCM authentication is verified when the relevant record is decrypted after Vault unlock. Full Android build and device-level restore tests remain outstanding.

### Post-unlock cryptographic integrity audit (development)

After a user unlocks the Vault, `VaultRepository.auditCryptographicIntegrity()` can authenticate every encrypted Vault record and Passkey private-key payload. It returns aggregate counts only and does not expose decrypted data. TOTP records are validated from their stored secret and parameters. Since schema v8, Passkey metadata also has a portable envelope protected by the Vault Key; the Android Keystore copy is a rebuildable device-local discovery cache. See the Passkey metadata portability section for the first-unlock behavior and remaining incremental-sync limitation.


### Passkey metadata portability (schema v8, development)

Passkey account metadata now has a portable AES-256-GCM envelope protected by the Vault Key, so it can be decrypted after restoring the encrypted Vault on another device. A separate Android Keystore-encrypted local cache remains for locked discovery and is rebuilt after the first unlock on the destination phone. Full encrypted database backups include this portable table; incremental per-record Passkey sync is not yet implemented. Room migration 7→8 and restore preflight checks are included. Device, Google Drive, and complete Android build validation are still pending.
