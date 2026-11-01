# Подключение Google Drive в sBoard

## Что уже реализовано

- Google Drive API v3 transport (`GoogleDriveBackupClient`).
- Поиск `sboard_backup.enc` в `appDataFolder`.
- Создание файла при первом backup и обновление существующего при следующих загрузках.
- Resumable upload блоками по 8 MiB и скачивание.
- Кнопка загрузки в экране «Профиль → Резервное копирование».
- OAuth authorization через Google Identity Services с запросом только `drive.appdata` непосредственно по нажатию кнопки.
- В Drive отправляется только предварительно зашифрованный `.enc` файл.

Google рекомендует запрашивать Drive-разрешения непосредственно при действии пользователя и использовать `AuthorizationClient` для авторизации доступа к данным Google. См. Android Developers: https://developer.android.com/identity/authorization.

## Настройка Google Cloud

1. Создайте проект в Google Cloud Console.
2. Включите Google Drive API.
3. Настройте OAuth consent screen / Branding.
4. Создайте OAuth client типа **Android**.
5. Укажите package name: `com.sboard.app`.
6. Укажите SHA-1 сертификата подписи установленного APK. Получить его локально можно командой `./gradlew signingReport`; Android OAuth-клиент проверяет одновременно package name и SHA-1. После изменения сертификата клиента установите APK, подписанный тем же ключом.
7. Убедитесь, что тестовый Google-аккаунт добавлен в Test users, если consent screen находится в режиме Testing.
8. Установите приложение на Android-устройство с Google Play services и войдите в нужный Google-аккаунт.

Для прямого client-side доступа к Drive через AuthorizationClient Web client ID не требуется. Web client нужен, если в будущем будет запрашиваться server auth code и сервер будет получать offline access / refresh token.

## Использование

1. Откройте sBoard → Профиль → Резервная копия.
2. Введите Recovery Key.
3. Нажмите «Загрузить зашифрованную копию в Google Drive».
4. При первом запросе подтвердите доступ к данным приложения.
5. Дождитесь сообщения об успешной загрузке.

Запрашивается только scope `https://www.googleapis.com/auth/drive.appdata`. Это специальная область для данных приложения; файлы в ней скрыты от обычного списка Drive и доступны самому приложению.

## Важные ограничения

- Google Drive не получает Recovery Key, мастер-пароль или открытый Vault Key.
- Access token не сохраняется приложением; Google Identity управляет своим краткоживущим кэшем. Токен используется только в заголовке текущего HTTPS-запроса.
- Если пользователь отозвал разрешение, фоновый worker не сможет показать окно согласия; откройте настройки резервирования и повторно подключите Google Drive.
- В актуальном development increment добавлен WorkManager backup по изменениям Room-базы. Для него требуется отдельное явное согласие на сохранение Recovery Key в Android Keystore; сам ключ и OAuth access token не помещаются в WorkManager inputData.
- Скачанный файл всё равно должен пройти проверку AES-GCM и восстановление через `EncryptedBackupManager`; само наличие файла в Drive не доказывает его подлинность.
- Google может удалить `appDataFolder`, если пользователь удалит приложение из своего Google Drive.

## Сборка

Зависимость Google Play Services Auth 22.0.0 уже добавлена в `app/build.gradle.kts`. Для сборки нужен Android SDK 36, JDK 17 и Gradle Sync. Debug APK из GitHub Actions подписывается временным runner debug-ключом: его SHA-1 меняется между сборками, поэтому для постоянной OAuth-настройки нужно подписывать APK постоянным ключом, хранящимся локально или в защищённых GitHub Actions secrets (не в репозитории).

## Event-triggered background backup (new development stage)

1. Open **Profile → Backup settings** and tap **Connect Google Drive**.
2. Approve the `drive.appdata` permission.
3. Explicitly save the Recovery Key for background use. It is encrypted with a non-exportable Android Keystore key; keep a separate offline copy.
4. Enable automatic backups.
5. After any committed Vault/Passkey insert, edit, or deletion, sBoard schedules a background encrypted snapshot. A 3-second debounce combines quick consecutive changes; if offline, WorkManager waits for a network connection.

Google Identity is asked for a short-lived access token when a worker runs. sBoard does not store that token. If the Google grant is revoked or Google requires renewed interactive consent, the worker cannot display a consent screen and skips the upload; reopen Backup settings and reconnect. The settings screen currently disables automation but does not yet call Google's revoke-access API.

This is a one-way encrypted backup, not bidirectional multi-device sync. Daily periodic scheduling is not used by the current settings UI. The new flow is source-integrated but still requires a Gradle build and real Android/Google Drive testing before it can be considered reliable.

## Backup status and manual run

The Backup Settings screen stores only operational metadata: timestamps, a result code, a trigger reason, and a short sanitized error message. It does not store Google access tokens, Recovery Keys, or Vault contents. Use **Back up now** to queue a one-off upload; this does not turn on automatic backups. Automatic backups remain event-driven and are scheduled after committed Room changes, with a short debounce to combine rapid edits. A network connection and the device-bound Recovery Key opt-in are required for unattended uploads.


## Upload integrity verification (latest development increment)

Versioned Google Drive uploads now calculate the encrypted local file size and MD5 before upload, then fail closed unless Drive's completed-upload metadata reports the same size and a matching 32-character MD5 checksum. This prevents the backup worker from recording success when the completed upload metadata is missing or inconsistent. This checksum is a transport-integrity check only; authenticity and tamper detection remain provided by the authenticated encrypted backup format (AES-GCM). Five pure policy tests cover matching metadata, missing checksum, size mismatch, checksum mismatch, and malformed checksum.

The Android Gradle build and device-level Drive upload have not been run in this environment; the integration still requires a real Google OAuth grant and Android verification.


## Google Drive disconnect and OAuth revocation (latest development increment)

The backup settings screen now offers an explicit Google Drive disconnect action. After confirmation, sBoard calls Google Identity `AuthorizationClient.revokeAccess()` for the app's requested `drive.appdata` scope. On success, the app clears its local consent flag, disables event-triggered backup scheduling, and leaves the local Vault, device-bound Recovery Key, and already uploaded Drive backup files untouched. If revocation fails, the UI keeps the previous connected state and reports the failure. Google documents that `revokeAccess()` clears cached tokens and revokes all scopes previously granted to this app; sBoard currently requests only `drive.appdata`.

This source change has not been validated by a full Android Gradle build or on-device OAuth test.
