# Build status — sBoard 0.1.0 development

- Source archive: created from the previous OTP-enabled project and extended with encrypted Passkey storage.
- Room schema: version 4; migration 3 -> 4 creates `passkey_records` and RP ID index.
- Passkey private-key storage: AES-256-GCM, wrapped by the unlocked Vault Key; AAD binds record ID and RP ID.
- Unit tests added for private-key round-trip and RP-ID substitution rejection.
- Russian and English app string resources retained from the previous project.
- Existing IME, Autofill, OTP and Vault sources retained.

## Not verified

- No successful Gradle build or APK was produced in this environment.
- Unit tests have not been executed.
- Passkey Credential Provider service and full WebAuthn ceremonies are not implemented; see `PASSKEY_PROVIDER_STATUS.md`.
- Google OAuth credentials and live Drive sync are not configured or device-tested.
- IME and Autofill have not been tested on a physical device.

Do not use this development build to store production secrets until the full security review, migration tests, build and device tests are complete.


## Passkey provider discovery stage

Added the Android 14+ Credential Manager provider registration and metadata-only passkey discovery. See `PASSKEY_PROVIDER_STATUS.md` for exact scope and known limitations. The final WebAuthn signing and registration ceremonies are intentionally not enabled yet. No APK build was performed in this environment.

## WebAuthn assertion core

- Added a standalone assertion builder for ES256 and RS256 keys.
- Added tests that verify an ES256 signature and reject RP ID mismatch.
- The assertion engine is not yet wired into the Android provider flow; selection remains fail-closed until trusted origin verification, biometric gating, and Credential Manager response wiring are complete.
- Gradle build and tests remain unverified in this environment.

## WebAuthn registration core

- Added standalone `WebAuthnRegistrationEngine` for ES256 passkey creation, COSE EC2 public-key encoding, `webauthn.create` client data, authenticator data, and CBOR `none` attestation object.
- Added tests for registration output structure, RP ID mismatch, missing ES256 support, and user-verification enforcement.
- Replaced Android-specific Base64 calls in the assertion engine with `java.util.Base64` to make the cryptographic core usable in JVM tests as well as Android.
- Registration remains disconnected from Android Credential Manager; see `PASSKEY_PROVIDER_STATUS.md` for required integration and security work.
- Gradle build and unit tests have not been run; no APK was produced.


## WebAuthn origin policy hardening

- Both `WebAuthnAssertionEngine` and `WebAuthnRegistrationEngine` now use a shared conservative HTTPS origin-to-RP-ID relationship policy.
- Added tests for unrelated domains, lookalike suffixes, malformed RP IDs, non-HTTPS origins and URL components that are not allowed in a serialized origin.
- The origin policy source was compiled with the standalone Kotlin compiler and its positive/negative checks were executed successfully. This is not a substitute for the full Android Gradle build or the JUnit suite.
- Public Suffix List validation and Android Digital Asset Links / calling-app verification remain integration requirements.

## Passkey biometric authorization gate

- Added a 30-second, single-use authorization grant bound to one Passkey record.
- Private-key retrieval now requires and consumes this grant.
- Added a completion method that only issues the grant after successful Keystore-backed biometric decryption.
- Added unit tests for credential binding, single-use behavior, expiry, and clock rollback.
- Android UI integration is not yet connected; Credential Provider selection remains fail-closed.
- Gradle build and Android/JUnit suite were not run in this environment.


## Passkey assertion pipeline orchestration

- Added an orchestration layer for allowCredentials matching, selected-record metadata lookup, biometric grant consumption, EC private-key decoding, assertion generation, and sign-counter / last-used metadata persistence.
- Private-key bytes are wiped after signing; assertion failures do not update usage metadata.
- Added JVM tests for successful ES256 assertion generation and allowCredentials rejection before private-key access.
- Android `PasskeySelectionActivity` remains fail-closed pending trusted calling-app origin retrieval, BiometricPrompt UI integration, and final Credential Manager response wiring.
- Full Gradle build and JUnit execution have not been run; no APK was produced.

## Android calling-app origin foundation

- Added a resolver based on `BeginGetCredentialRequest` / `CallingAppInfo` identity, with Android APK signing-certificate origin formatting.
- The privileged browser allowlist is empty by design; delegated origins are rejected until trusted browser signing fingerprints are explicitly configured.
- Added JVM tests for the pure origin formatter.
- Resolver is not connected to the selection activity or assertion pipeline. Android app-origin handling, DAL/ROR validation and response wiring remain incomplete. No Gradle build or APK was produced.

## Digital Asset Links verification foundation

- Added an HTTPS-only Digital Asset Links fetcher with redirects disabled, timeouts, and a 256 KiB response cap.
- Added exact package, `delegate_permission/common.get_login_creds` relation, and SHA-256 certificate fingerprint matching.
- Calling-app origin resolver now requires a matching RP-hosted statement before deriving a native Android origin.
- Added four JVM matcher tests. They were added but not run; no Gradle build or APK was produced.
- Resolver and Android origin support are not yet connected to the final Credential Manager selection response. Public Suffix List / Related Origin Requests and live RP/device tests remain outstanding.

## 2026-10-31 — trusted assertion coordination

Added `TrustedAssertionCoordinator.kt` to compose the existing CallingAppInfo/Digital Asset Links
origin resolver with the biometric-gated assertion pipeline. The coordinator refuses invalid RP IDs
and origins before key access. Android Activity prompt wiring and final Credential Manager response
serialization remain incomplete. No full Gradle build, APK build, or device test was run for this stage.

## Latest Passkey UI integration update

- `PasskeySelectionActivity` now contains a first-pass `BiometricPrompt` + assertion + Credential Manager response path.
- It requires exactly one `GetPublicKeyCredentialOption`, rejects non-null `clientDataHash`, and only returns a credential after trusted-origin resolution and a successful signature.
- Biometric cancellation returns `RESULT_CANCELED`; failures return a provider exception where possible.
- **Not verified:** full Gradle build, Android instrumentation tests, Credential Manager end-to-end flow, API 34+ device behavior, biometric enrollment/invalidation, and Digital Asset Links network verification.
- **Known limitation:** provider discovery still checks `VaultSession.isUnlocked()` before offering entries; therefore a locked Vault will not show passkeys in the system selector yet.

## 2026-10-31 — Passkey discovery while Vault is locked

Credential Provider discovery now queries a dedicated metadata projection without checking whether the Vault session is unlocked. The projection excludes the encrypted private-key blob, IV, and COSE public key. The selection flow still requires a successful biometric prompt before unwrapping the Vault key and creating a one-time Passkey authorization. Added tests for allowCredentials matching and projection field boundaries. Gradle/Room/JUnit/device verification remains pending.

## 2026-10-31 — encrypted Passkey discovery metadata

- Room schema advanced from version 4 to 5 with a dedicated secure metadata envelope table.
- New Passkey labels, RP ID, user handle and COSE public key are AES-GCM encrypted using a separate Android Keystore key; RP lookup uses a keyed HMAC blind index.
- Credential IDs remain available for Credential Manager matching; private-key ciphertext remains protected by the Vault key.
- Added lazy migration/scrubbing for older plaintext Passkey metadata and RP-index integrity checks.
- Android Keystore operations and Room migration have not been executed here. No Gradle build, JUnit run, APK, or device test was performed. Treat this as development source, not production-ready storage.

## 2026-10-31 — Passkey integrity audit

- Added a read-only integrity audit for encrypted metadata, keyed-index consistency, missing/orphan
  envelopes, legacy plaintext rows, and basic credential ciphertext/IV/counter invariants.
- Added explicit classification for Android Keystore key-unavailable/invalidation failures.
- Added JVM tests for integrity policy and backup-restore recommendations.
- No automatic deletion, key reset, or speculative repair is performed. Damaged records fail closed;
  recovery requires a verified backup or re-registering the credential.
- Full Gradle/Room/JUnit and physical-device verification have not been run; no APK was produced.


## 2026-10-31 — authenticated restore preflight and rollback safety

- Restore staging files are now created beside the live database so atomic replacement stays on the same filesystem.
- Before the live Vault is locked or replaced, the backup must decrypt completely, pass the SQLite header check, pass read-only `PRAGMA quick_check`, and report a Room schema version supported by the current migration chain (1–5).
- After closing Room, WAL/SHM sidecars must be removed successfully. The previous database is copied and synced to a rollback file before replacement; a failed replacement attempts to restore it.
- Added JVM policy tests for supported/unsupported schema versions. Android SQLite validation and full restore flow still require device/instrumentation testing. Full Gradle build, Room KAPT, and JUnit suite were not run; no APK was produced.

## 2026-10-31 — versioned Google Drive backup history

- Added immutable versioned uploads (`sboard_backup_v1_<epoch>_<random>.enc`) in Drive `appDataFolder`; the existing single-file `uploadOrReplace` API remains for backwards compatibility.
- Added paginated `listVersions` and explicit `downloadVersion` APIs so a selected historical snapshot can be downloaded without replacing another version.
- Drive MD5 checksum is requested and verified against the downloaded encrypted file before it is moved into the destination path. This is a transport-integrity check; AES-GCM authentication and restore preflight remain the security boundary.
- Backup screen now uploads new snapshots instead of overwriting the previous snapshot, can display the version history, and can restore a selected version after the Recovery Key is entered.
- Added a pure version naming/retention policy. Retention only produces a preview of candidates; no cloud backup is automatically deleted.
- Added unit tests for timestamp naming, ordering, malformed names, and retention preview. The pure policy compiled with `kotlinc` and its standalone smoke checks passed.
- Not verified: full Gradle/Compose compilation, Google OAuth, Drive API requests, end-to-end upload/download, and device restore. No APK was produced. Existing backups under the legacy canonical name are not yet migrated into the version list.


## Backup automation stage

- Added WorkManager dependency and scheduler for periodic and important-change backup work.
- Work constraints: connected network and battery not low; exponential retry backoff.
- No credentials or Recovery Keys are passed via WorkManager Data.
- Worker intentionally does not perform a backup unless `BackupAutomationRuntime.executor` is registered by the application. That secure runtime/OAuth integration and UI settings are not wired in this stage; therefore scheduled backup is infrastructure only, not an end-to-end enabled feature.
- Gradle build and device tests were not run in this environment.

## 2026-10-31 — Backup automation execution engine

- Added `DriveBackupAutomationExecutor`: serializes backup runs, requires an unlocked Vault session and short-lived Recovery Key / Drive token, creates an encrypted staging snapshot, and uploads it as a new immutable Drive version.
- Added `BackupAutomationCredentialProvider` as an explicit runtime integration contract. Implementations must acquire credentials at execution time and must not persist them in WorkManager input, preferences, or logs.
- Runtime installation is explicit. No provider is registered by the current app, because unattended access to the Recovery Key is not yet designed and interactive Google consent cannot be shown by a background Worker. Until that is implemented, scheduled work safely skips or retries without uploading data.
- Added pure readiness-policy coverage. Full Gradle build, JUnit, live OAuth / Drive upload, and device testing were not run; no APK was produced.
- Worker now treats a missing runtime executor as a configuration failure instead of an endless retry loop; the periodic schedule remains available for a later correctly configured run.

## 2026-10-31 — device-bound backup credential foundation

- Added an explicit opt-in store for the Recovery Key, encrypted with an AES-256-GCM key held by Android Keystore; only IV and ciphertext are stored in app-private preferences.
- Added a provider that can assemble scheduled-backup credentials only when the in-memory Vault session is already unlocked, the device-bound Recovery Key is configured, and a silent Drive token provider returns a token. Workers never launch consent UI.
- Added pure policy tests for Recovery Key format and all readiness gates.
- Important limitation: this does not make a locked Vault available to background work. If Android has restarted the process, the Vault session is locked and the backup is skipped. OAuth silent-token acquisition still requires an app-supplied implementation and prior user authorization. Device loss/uninstall/Keystore invalidation removes access to this convenience copy; users must retain their offline Recovery Key.
- Android Keystore runtime, Gradle build, JUnit, OAuth and live Drive upload were not executed in this environment. No APK was produced.

## Backup & Sync settings UI stage

- Added a Profile entry and bilingual Compose screen for backup interval, desired automation state, version-retention preference, and device-bound Recovery Key opt-in/removal.
- Only non-secret preferences are stored in `BackupAutomationSettingsStore`.
- Recovery Key entry uses transient Compose state (not saveable state) and is written only through `DeviceBoundBackupKeyStore` after explicit confirmation.
- The screen does not claim background backups are active when `BackupAutomationRuntime.executor` is absent. The keep-versions value is a preference only; automatic retention/deletion is not wired.
- Added unit tests for interval and retention bounds.
- Full Android Gradle build, Room KAPT, JUnit execution, UI testing, and physical-device validation were not run for this stage.
- Local smoke check: `BackupAutomationPolicy` compiled with `kotlinc`; 8 interval/retention boundary assertions passed. `MainActivity.kt` passed a Kotlin parser-only check; unresolved Android/Compose symbols are expected because no Gradle classpath was available.

## WebAuthn registration request hardening

- Added `WebAuthnRegistrationPolicy`, a strict allow-list validator used by `WebAuthnRegistrationEngine` before key generation.
- Validates bounded request size, RP ID and origin relationship, challenge encoding/length, user handle bounds, RP/user labels, ES256 support, platform attachment constraints, resident-key option consistency, UV policy, `none` attestation, and unsupported extensions.
- Parses and bounds `excludeCredentials` IDs for the provider layer to use when registration is connected.
- Added JVM unit tests for accepted requests, malformed/short challenges, unsupported extensions, attachment mismatch, and exclusion ID parsing.
- This is registration-core hardening only. `SBoardCredentialProviderService.onBeginCreateCredentialRequest` still returns unsupported; end-to-end provider registration is not yet implemented. Gradle compilation and tests remain unverified in this environment.

## 2026-10-31 — event-driven backup + Google OAuth runtime wiring

- Added `SBoardApplication` process startup wiring: registers the secure backup executor and observes Room invalidations for Vault records, Passkey records/metadata, Vault configuration, recovery envelope, and biometric envelope.
- A committed insert/update/delete now requests a one-time WorkManager backup. The scheduler waits 3 seconds to coalesce rapid edits, requires a network connection, and replaces a pending change task so a burst produces one fresh snapshot. Daily periodic scheduling is no longer enabled by the settings UI.
- Added a Google Identity silent token provider for `drive.appdata`. It asks Google for a short-lived token at worker runtime; it never writes the access token or refresh token to sBoard preferences or WorkManager data. If Google requires an interactive consent step, the worker safely skips; the user must reconnect in the settings screen.
- Added a Google Drive connect action to Backup Settings. It stores only a local boolean indicating that consent was completed, not an OAuth token. The Google Identity SDK owns its own token cache.
- The background snapshot can run while the Vault UI is locked: the Room database already stores Vault payloads encrypted, and the backup envelope is encrypted again with the explicitly opted-in device-bound Recovery Key. The worker does not unlock the Vault or export plaintext records.
- HTTP 401/403 from Drive are treated as authorization failures rather than endless transient retries. Other I/O failures remain retryable.
- Still not implemented: OAuth revoke/disconnect at Google (the current UI can disable automation but does not revoke Google's grant), automatic retention cleanup, a durable user-visible pending/error state for skipped uploads, and end-to-end sync conflict handling across multiple devices. This is encrypted backup upload, not bidirectional live database sync.
- Not verified: Gradle/Compose/KAPT compilation, JUnit, Google OAuth consent, WorkManager execution after process death/reboot, Room invalidation behavior on device, and real Drive upload/restore. No APK was produced. This source remains a development increment.

## 2026-10-31 — backup run visibility and manual execution

- Added a non-secret backup status ledger: last attempt, last successful snapshot, result state, trigger reason, and a bounded/scrubbed diagnostic message. OAuth tokens, Recovery Keys, and Vault content are never written to this ledger.
- WorkManager worker now records start/success/skipped/failure outcomes. Authorization or missing device credentials are reported as requiring attention rather than silently looking like a successful upload.
- Added a manual “Back up now” action that schedules an immediate network-constrained worker and does not require enabling automatic backups.
- Added a refreshable status panel in Backup Settings, including last successful copy and last attempt timestamps; Russian and English labels are provided.
- The event-driven Room observer and 3-second debounce remain unchanged. A committed Vault/Passkey/security-table change triggers a fresh encrypted snapshot when automation is enabled; deletes are reflected because each snapshot represents the current complete encrypted database.
- Verification remains limited: source inspection and archive integrity checks only. Gradle/Compose/KAPT, JUnit, WorkManager lifecycle, OAuth consent, and real Drive upload were not run. No APK was produced.


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

## Explicit Google Drive restore coordinator (latest development increment)
- Added `DriveBackupRestoreCoordinator` for listing versioned snapshots and restoring one explicitly selected version.
- The coordinator re-queries Drive before download and requires an exact current ID/name match, preventing stale selections from being restored.
- Restore uses the existing encrypted download checks, Recovery Key validation, per-chunk AES-GCM authentication, SQLite `quick_check`, schema compatibility validation, and rollback-protected database replacement.
- Downloaded encrypted staging files are deleted in `finally`; credentials are passed in memory only and are not stored by the coordinator.
- Added five selection-policy unit tests.
- This is a restore orchestration layer, not yet a complete onboarding UI. The app still needs a user-facing screen, confirmation step, recovery-key entry UX, and post-restore database/session reinitialization flow.
- Full Android Gradle build and device-level Google Drive restore remain unverified in this environment.


## 2026-10-31 — first-run Drive restore screen

- Added a separate Profile entry for restoring onto a replacement/new device; it is distinct from ordinary backup management.
- The screen requests the narrow `drive.appdata` OAuth scope, lists versioned sBoard snapshots, shows timestamp/size metadata, and keeps the access token only in transient Compose memory. No token or Recovery Key is persisted by this screen.
- The user must select a snapshot, enter the 43-character Recovery Key, and confirm database replacement. The restore coordinator re-lists Drive metadata, downloads to a temporary encrypted file, verifies transport metadata where available, authenticates/decrypts the backup, validates SQLite integrity/schema, and replaces the local database with rollback protection.
- After restore, the UI directs the user to the Vault tab to unlock with the original master password stored inside the restored database.
- Russian and English UI strings were added.
- Not verified: full Gradle/Compose build, Android OAuth consent, live Drive listing/download, restore on a clean install, and physical-device testing. The screen is source-level implementation only; no APK is produced.

## First-run security setup wizard (latest development increment)

- Added startup detection for an unfinished first-run setup using the Vault master-password configuration and a non-secret setup-completion preference.
- New installs are routed to a guided local Vault setup: create a master password, generate a Recovery Key, show it in-memory, offer sensitive clipboard copy, and require explicit acknowledgement that it was saved separately.
- If setup is interrupted after the master-password record is committed, the next launch asks the user to unlock the just-created Vault and issue a fresh Recovery Key. Secret text fields and the displayed Recovery Key use non-saveable Compose state so they are not written into saved-instance state.
- Existing installations that already have a master password are not forced to rotate Recovery Keys; this avoids invalidating backups made before this wizard existed.
- The first-run wizard offers a route to the existing Google Drive restore screen. Google Drive OAuth connection remains a separate profile step; it is not yet embedded in the wizard.
- Restore success marks first-run setup complete.
- Full Android/Gradle build, Compose UI tests, device tests, and Google OAuth/Drive flows remain unverified in this environment.


## Google Drive disconnect and OAuth revocation (latest development increment)

The backup settings screen now offers an explicit Google Drive disconnect action. After confirmation, sBoard calls Google Identity `AuthorizationClient.revokeAccess()` for the app's requested `drive.appdata` scope. On success, the app clears its local consent flag, disables event-triggered backup scheduling, and leaves the local Vault, device-bound Recovery Key, and already uploaded Drive backup files untouched. If revocation fails, the UI keeps the previous connected state and reports the failure. Google documents that `revokeAccess()` clears cached tokens and revokes all scopes previously granted to this app; sBoard currently requests only `drive.appdata`.

This source change has not been validated by a full Android Gradle build or on-device OAuth test.

## Multi-device sync groundwork — local change journal and conservative conflict policy

- Added Room schema v6 with `sync_change_journal`, storing only record IDs, operation type, installation ID, monotonic local revision, and timestamp. No password, Vault payload, passkey private key, Recovery Key, or OAuth token is stored in the journal.
- Vault record create/update and delete now write their journal event in the same Room transaction as the data mutation. This prevents a committed Vault change from being recorded without its corresponding journal entry, or vice versa.
- Added a stable per-installation UUID and a persisted Lamport counter. The counter can advance beyond a remote counter once a future sync transport has observed one.
- Added a deliberately conservative conflict policy: revisions from different devices are not assumed to be comparable. Different content from separate devices is surfaced as a conflict rather than silently overwriting a password. Same-device revisions can be ordered; identical fingerprints are treated as no change.
- Added six JVM tests for the conflict decision policy.
- Scope limitation: this is sync groundwork, not live multi-device sync. There is not yet a Drive change-feed/manifest transport, remote journal import, per-record encrypted payload exchange, passkey journal integration, conflict-resolution UI, or device-pairing screen. Existing Drive uploads remain encrypted whole-database snapshots and must not be described as bidirectional sync.
- Verification: source and migration were inspected; full Gradle/KAPT/JUnit and Room migration tests remain unrun in this environment. No APK produced.

## 2026-10-31 — authenticated encrypted sync envelope

- Added a versioned binary envelope for individual Vault journal changes.
- Journal metadata and opaque encrypted row data are protected with AES-256-GCM; the envelope key is domain-separated from the 256-bit Vault key using HMAC-SHA-256.
- UPSERT requires an encrypted row payload; DELETE carries a tombstone without record bytes. Format version/header are authenticated as AAD. Size and field bounds are enforced before allocation/decoding.
- Added six JVM unit tests for UPSERT/DELETE round trips and invalid/tampered payloads.
- Standalone Kotlin compilation passed. A JVM smoke harness passed round-trip, confidentiality, tombstone, tamper and wrong-key checks.
- This is not Drive transport integration or live multi-device sync. Remote manifest, event upload/download, acknowledgement/deduplication, replay protection and conflict UI remain outstanding. Full Gradle/JUnit/Android build and device tests were not run.

## 2026-10-31 — Google Drive encrypted sync transport foundation

- Added `GoogleDriveSyncTransport` using Drive API v3 `appDataFolder` for individual encrypted sync envelopes (separate from whole-database backup snapshots).
- Supports listing remote sync envelopes, downloading a selected envelope, and uploading one envelope with a stable filename derived from `changeId`.
- Upload retry is idempotent: if the same change ID already exists, the transport downloads it and requires byte-for-byte equality instead of creating a duplicate or silently overwriting a different payload.
- The transport accepts short-lived OAuth access tokens as call arguments only; it does not persist tokens, Vault keys, Recovery Keys, or plaintext Vault records.
- Added strict filename/ID and 4 MiB envelope-size policy plus five JVM unit tests.
- Important architecture clarification: `appDataFolder` is hidden from the normal Drive UI and only accessible to the same app. The same Google account on multiple devices can access it through the same sBoard app identity and `drive.appdata` authorization. It is not a user-visible/shared folder. If visible files are required, a separate design using a user-visible sBoard folder and an appropriate Drive scope is needed.
- Not yet live sync: this increment adds the Drive transport, but does not yet wire local journal entries into encrypted envelopes, import remote changes into Room, track acknowledgements/replay, or schedule sync on app launch/resume and local mutations. No automatic merge is claimed.
- Verification: pure Kotlin `GoogleDriveSyncPolicy` compilation and tests are checked separately; full Android Gradle build, live OAuth/Drive requests, transport integration tests, and two-device tests remain unverified.


## 2026-10-31 — Google Drive Vault exchange coordinator

Room schema v7 adds journal delivery state and an encrypted incoming-change inbox. A new coordinator uploads the latest pending encrypted state per Vault record, uploads tombstones for deletions, then downloads and authenticates remote envelopes. Own-device events and duplicate inbox events are filtered. Incoming changes are staged as encrypted envelopes and are not silently applied to local records; a user-visible conflict-resolution and apply flow is still required. Pure sync-exchange policy compiles independently. Full Gradle build, Room migration validation, JUnit, live OAuth/Drive and multi-device tests remain unverified.

- Upload retry safety: generated AES-GCM sync envelopes are cached in the local outbox before upload, so process death after a successful remote upload can retry byte-for-byte without generating a different nonce/payload for the same change ID.

## 2026-10-31 development increment — explicit sync inbox resolution

Added a transactional `VaultSyncInboxManager` for incoming encrypted per-record changes. It validates the authenticated envelope against the staged inbox metadata, verifies the inner Vault AES-GCM record payload with record-ID AAD, and supports explicit `ACCEPT_REMOTE` / `KEEP_LOCAL` decisions. The selected final state is recorded as a fresh local journal event so the decision can propagate to other devices. Added inbox DAO lookup/state-transition methods and four pure policy tests.

Verification in this environment: the manager and its directly referenced sync/entity source compile with lightweight Android/Room API stubs; the pure resolution policy compiles. This is only a syntax/API-shape check, not an Android build or Room processor validation. Full Gradle build, Room migration/DAO generation, JUnit suite and real Drive/two-device testing were not run. A user-facing conflict review screen and automatic sync scheduling are still pending.

## 2026-10-31 — event-triggered Google Drive sync worker

- Added a WorkManager one-time sync worker and unique-work scheduler. Local journal changes schedule an exchange after a short 3-second debounce; returning to the app or unlocking the Vault requests an immediate pass. No daily/periodic sync timer is installed.
- Added a process runtime executor that checks the Vault is unlocked, obtains a short-lived `drive.appdata` token silently through Google Identity, and invokes the existing Google Drive Vault exchange coordinator. OAuth tokens and Vault keys are not placed in WorkManager input or persisted by the scheduler.
- Wired Room invalidation for `sync_change_journal` to schedule outbound sync after committed local writes. Wired foreground and successful Vault unlock to request a catch-up pass.
- Important limitations: WorkManager can run only when the process runtime is available and the Vault is unlocked; if the user must interact with Google consent, the worker exits without syncing and the next foreground/unlock pass retries. Incoming events remain encrypted in the inbox and still require explicit review; automatic application of non-conflicting changes and conflict UI are not yet complete. Passkey changes are not yet journaled as sync events.
- Verification: source-level inspection only in this increment. Full Android Gradle/KAPT build, worker execution, OAuth token acquisition, Room invalidation behavior and two-device Drive tests remain unverified.

## 2026-10-31 — sync review UI increment

- Added a pending incoming-sync review panel to the unlocked Vault screen.
- The panel displays only authenticated-envelope review metadata; it does not decrypt or display secret fields.
- Added explicit local/remote resolution actions wired to `VaultSyncInboxManager`, with progress state and inbox refresh.
- Added Russian/English translations.
- Verification limitation: no Gradle wrapper or installed Gradle executable was available, so a full Android Compose/Room build was not run. Google OAuth/Drive and two-device behavior remain unverified.


## 2026-10-31 — offline-safe sync scheduling

- Sync work is now enqueued after local journal changes even when the device is offline.
- WorkManager's `NetworkType.CONNECTED` constraint keeps the one-time request pending until connectivity returns, instead of dropping the request at scheduling time.
- Foreground/Vault-available requests use the same durable scheduler path.
- The worker still acquires OAuth credentials and Vault access at execution time; no token or Vault key is placed in WorkManager input data.
- Limitation: if the user revokes Drive access or the Vault remains locked when the worker runs, the worker exits as `NEEDS_UNLOCK_OR_AUTH`; foreground entry or a later local change schedules another attempt. This is not an always-on sync daemon.
- Not verified: Gradle build, JUnit suite, WorkManager behavior on device, network restoration, OAuth refresh/revocation, and multi-device end-to-end sync.


## 2026-10-31 — replacement-device replay protection

- Fixed an important first-restore edge case: after restoring a full database on a new installation, its local installation ID is new, but the restored change journal still contains old change IDs. The sync coordinator now checks each authenticated incoming event against the restored journal before adding it to the inbox.
- This prevents historical Drive events already represented by the snapshot from being mistaken for fresh remote edits and flooding the conflict/review list.
- Sync result now reports `alreadyKnownChanges` separately from events ignored because they came from the current installation and events already staged in the inbox.
- Added a JVM test covering restored-journal replay suppression.
- Not verified: Gradle/JUnit execution (no Gradle wrapper or installed Gradle executable in the environment), Room KAPT/DAO generation, live Google OAuth/Drive calls, clean-install restore, and physical two-device sync.

## 2026-10-31 — restore schema compatibility aligned with Room

- Fixed a restore blocker: `RestoreDatabaseValidator` previously accepted schema versions 1–5 while `VaultDatabase` was already at schema version 7.
- Introduced `VAULT_DATABASE_SCHEMA_VERSION` as the single source of truth used by both the Room `@Database` annotation and restore compatibility policy.
- Restore policy now accepts schema versions 1 through the current Room schema version (7); future versions remain rejected.
- Updated `RestoreCompatibilityPolicyTest` to cover the current schema and reject the next unsupported version.
- Source-level verification: checked the declared migration chain 1→2→3→4→5→6→7 and verified the policy references the shared schema constant. Full Gradle/JUnit execution and device restore remain unverified because this project snapshot has no Gradle wrapper and no Gradle executable is available in the environment.

## 2026-10-31 — WAL checkpoint before restore rollback snapshot

- Restore now explicitly runs `PRAGMA wal_checkpoint(TRUNCATE)` against an existing Room Vault before locking the session and closing the database singleton.
- Restore proceeds only when SQLite reports no busy checkpoint and all WAL frames are checkpointed (or WAL mode is not active).
- This ensures the rollback copy of the main database includes committed WAL changes before sidecar cleanup and replacement.
- Added pure JVM tests for complete, busy, partial, and inconsistent checkpoint results.
- Not verified: Gradle/JUnit execution, SQLite behavior on physical devices, forced process termination during replacement, and full restore end-to-end.

## 2026-10-31 — crash-safe restore transaction journal

- Added a durable restore marker written and fsynced before the atomic database replacement.
- The marker records whether a previous Vault existed, the rollback filename, and the rollback SHA-256 digest; it contains no recovery key or Vault secret.
- On application startup, before Room opens the database, interrupted restores are rolled back. A first-time restore interrupted before commit removes the incomplete database instead of exposing it.
- The rollback copy is checked against its digest and SQLite integrity/schema policy before it is restored. If the journal or rollback material is inconsistent, startup fails closed rather than opening a potentially damaged Vault.
- The new database is validated after atomic replacement; the journal is removed only after successful validation.
- Validation limitation: no Gradle wrapper or installed Gradle is available in this environment, so Android build, JUnit and device power-loss tests remain unverified.

## 2026-10-31 — centralized restore preflight validation

- Centralized Recovery Key format and encrypted backup size validation in `BackupRestorePreflightPolicy`.
- Both local restore and Google Drive restore now use the same checks before database or download work proceeds.
- Added unit tests for valid/invalid Recovery Key lengths and alphabet, plus backup size boundaries.
- This is source-level coverage; Android Gradle/JUnit execution remains unverified because this archive has no Gradle Wrapper.

## 2026-10-31 — fail-closed metadata validation before Drive restore

- Added `DriveBackupDownloadPolicy` for immutable versioned snapshots.
- `downloadVersion` now requires a positive bounded Drive-reported size and a well-formed MD5 checksum before starting the download.
- Existing post-download size and MD5 comparisons remain in place; authenticated AES-GCM chunk verification remains the cryptographic authenticity check.
- Added unit tests for valid metadata, missing values, invalid checksums, and size boundaries.
- Verification limitation: full Gradle/JUnit and Android device tests have not been run in this environment.


## 2026-10-31 — legacy Drive backup restore compatibility

The restore discovery path now includes the canonical legacy `sboard_backup.enc` file alongside immutable versioned snapshots. The UI labels the legacy entry, the selection policy revalidates exact ID/name against a fresh Drive listing, and download still fails closed unless Drive size and MD5 metadata are present and match downloaded bytes. Legacy backups are not exposed to version deletion. Source-level changes and tests were added; full Gradle/JUnit, Google API, and device verification remain pending.

## 2026-10-31 — restore backup discovery preflight

- Added `BackupDiscoveryPolicy` to filter Google Drive entries before they are presented as selectable restore points.
- The discovery flow now applies the same strict size/MD5 metadata checks as the download path.
- The restore screen distinguishes an actually empty backup folder from a folder containing entries that cannot be safely verified; rejected-entry count is shown to the user.
- Added `BackupDiscoveryPolicyTest` for valid versioned backups, trusted legacy backups, missing metadata, malformed names, and empty results.
- The existing explicit Google authorization flow already triggers discovery immediately after authorization; this change hardens what is shown after discovery.
- Android Gradle/JUnit/device validation remains pending because Gradle/Gradle Wrapper is unavailable in this environment.


### 2026-10-31 — restore completion and Room rebind

- Restore now opens the installed database through Room before clearing the crash-recovery marker, so Room schema validation is part of the commit gate.
- Restore refuses a database without the master-password configuration envelope; this prevents reporting success for a database that cannot be unlocked through the normal Vault gate.
- If Room validation fails after the candidate file is installed, its singleton is closed before crash recovery restores the previous database.
- After successful replacement, the Room invalidation observer is rebound to the new database singleton.
- The Vault session remains locked; the user must unlock with the existing master password. This change does not claim a successful device-level or Gradle build test.

## 2026-10-31 — restore content integrity preflight

Extended `RestoreDatabaseValidator` beyond SQLite `quick_check` and schema version checks. Before replacing the active Vault database, it now verifies the required tables and columns for the detected Room schema, validates encrypted blob/IV lengths and bounded Argon2 parameters, and checks row metadata in Vault records, master-password configuration, recovery/biometric envelopes, Passkey records/secure metadata, sync journal, and sync inbox. It reports row counts for Vault records, Passkeys, journal changes, and inbox changes in the validation result. The validator uses schema-aware journal checks so schema v6 is not queried for v7-only columns. Resolved inbox states match the app's actual `RESOLVED_REMOTE` / `RESOLVED_LOCAL` values.

Validation performed for this source update: the extracted SQLite integrity SQL queries were executed against a schema-v7 SQLite fixture; accepted and corrupt inbox states were checked; ZIP integrity was checked. A full Android/Gradle build, JUnit run, device restore, and cryptographic decryption of every Vault/Passkey record were not performed. Encrypted payload authenticity is still enforced when the user unlocks/uses records; this preflight checks their structural shape, not plaintext correctness.

## 2026-10-31 — post-unlock cryptographic integrity audit

- Added `VaultCryptographicIntegrityAuditor`, invoked through `VaultRepository.auditCryptographicIntegrity()` while the Vault is unlocked.
- The audit decrypts every Vault record with AES-256-GCM and the record-ID AAD, parses the JSON payload, and verifies all six required fields without returning plaintext.
- OTP/TOTP-category records are validated using their stored Base32 secret and metadata parameters; no OTP code or secret is included in the report.
- Passkey private-key ciphertext is authenticated with the Vault key and RP-ID AAD, and the current ES256 PKCS#8 key format is parsed with the EC KeyFactory.
- The report contains aggregate counts and failure counts only; temporary Vault key, decrypted record payloads, and decrypted PKCS#8 bytes are wiped in `finally` blocks.
- Important portability limitation surfaced: current Passkey display metadata is encrypted with a non-exportable Android Keystore key (`PasskeyMetadataCipher`). A raw database restore onto another Android device cannot decrypt that metadata unless a migration/re-encryption design is added. The audit reports such Passkeys as failed rather than claiming their integrity. This is a remaining cross-device Passkey blocker, not a reason to delete or overwrite the records.
- Verification: source inspection and ZIP integrity only. Android Gradle/JUnit, device Keystore, and real cross-device tests remain unverified.


## 2026-10-31 — portable Passkey metadata and schema v8

- Added a Vault-Key-encrypted portable metadata envelope for each Passkey. The envelope uses AES-256-GCM with record-ID AAD and travels inside the encrypted database snapshot.
- Kept the Android Keystore envelope only as a rebuildable per-device discovery cache; its HMAC RP index is regenerated locally.
- New Passkey writes persist the main record, device cache, and portable envelope atomically through a Room DAO transaction.
- Added Room migration 7→8 and restore preflight checks for the portable metadata table and orphan/corrupt rows.
- Added automatic post-unlock cache rebuild attempts to master-password, Recovery Key, and biometric unlock flows; a damaged Passkey is skipped without blocking Vault unlock. Updated the aggregate cryptographic audit to prefer portable metadata.
- Existing v5-v7 metadata can be migrated on the source device while the Vault is unlocked. If the original device-bound key is already lost and no portable envelope exists, those old metadata rows cannot be reconstructed automatically.
- Important remaining limitation: this change makes Passkey metadata portable in full encrypted database backups. It does not yet add Passkey records to the incremental change journal; real-time incremental Passkey sync remains a separate task. The Credential Provider on a newly restored device may not show entries until the user unlocks sBoard once and the local discovery cache is rebuilt.
- Verification: standalone Kotlin compilation of the new portable cipher and SQLite table smoke test passed; source/migration/restore-validator review and ZIP integrity passed. No Gradle wrapper or installed Gradle executable is available, so full Android compilation, Room KAPT/migration tests, JUnit, Keystore, Drive, and physical two-device verification remain unverified.
