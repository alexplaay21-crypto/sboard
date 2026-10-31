# Passkey provider status — sBoard 0.1.0

## Implemented in this stage

- Added AndroidX Credential Manager provider dependency (`androidx.credentials:credentials:1.5.0`).
- Registered `SBoardCredentialProviderService` for Android 14+ (API 34+), with a capability declaration for public-key credentials.
- Kept the service disabled below API 34 through resource-qualified `bool` values.
- Added passkey discovery for the Vault (now available while locked): parses the WebAuthn `rpId`, honors non-empty `allowCredentials`, and returns metadata-only `PublicKeyCredentialEntry` objects.
- Uses unique mutable `PendingIntent`s for the system to attach the final request. Private key bytes are not read during discovery.
- Added a fail-closed selection activity. It returns a Credential Manager error rather than releasing a key or fabricating a WebAuthn assertion.
- Passkey creation currently returns an explicit unsupported error.
- Clear-credential-state is implemented as a no-op because sBoard does not maintain per-site sign-in session state.

## Important limitations — do not treat this as production-ready Passkey support

The Android provider is registered and can advertise matching saved passkeys, but the final WebAuthn ceremony is not complete yet. The following are still required before users can sign in or register through sBoard: 

1. A reviewed WebAuthn engine for `navigator.credentials.get()` and `create()` request/response processing.
2. Strict RP ID / calling-app origin verification, including Android Digital Asset Links validation for native apps.
3. Biometric or device-credential confirmation immediately before decrypting a selected private key.
4. WebAuthn assertion construction (`clientDataJSON`, authenticator data, signature, user handle) and correct signature-counter updates.
5. Registration parsing, COSE public-key validation, secure key generation, and encrypted persistence.
6. Tests against Android 14+ Credential Manager and real relying-party test sites.

The final activity deliberately fails closed. No private key is exposed and no fake assertion is returned.

## Device setup

On Android 14 or newer, after installing a build, enable sBoard under **Settings → Passwords, passkeys & accounts → Preferred service / Credential provider** (the exact labels vary by manufacturer). On older Android versions, the existing Autofill service remains the integration route.

## Build verification

This archive was updated at source level. A Gradle/Android SDK build was not run in this environment, so compilation and device behavior are not yet verified.

## WebAuthn assertion engine — development stage

Added `WebAuthnAssertionEngine`, which constructs the WebAuthn `webauthn.get` clientDataJSON, RP ID hash, 37-byte authenticatorData (UP+UV flags), signature counter, and ES256/RS256 signature over `authenticatorData || SHA-256(clientDataJSON)`. The result can be serialized into the standard PublicKeyCredential response JSON shape. Added JVM unit tests for ES256 signature verification and RP ID mismatch rejection.

Security boundary: this engine is deliberately not connected to `PasskeySelectionActivity` yet. The caller must obtain a verified origin from trusted Credential Manager calling-app identity / Digital Asset Links, require real user verification before decrypting the key, and pass only the selected record's private key. The current service still fails closed rather than returning a fake assertion. Registration (`webauthn.create`), attestation, COSE key encoding/decoding, origin association, and Credential Manager final response wiring remain unfinished.

## WebAuthn registration engine — development stage

Added a standalone `WebAuthnRegistrationEngine` for `webauthn.create` with ES256/P-256 key generation, a 32-byte random credential ID, deterministic COSE EC2 public-key encoding, `clientDataJSON`, authenticator data with UP+UV+AT flags and zero initial counter, and a CBOR `fmt=none` attestation object. The engine returns PKCS#8 private-key bytes to its caller but does not persist them; production integration must immediately pass them to the encrypted `PasskeyRepository` path and never expose them to UI/logging. It accepts only `attestation: none` and requires the RP to advertise ES256.

The registration engine is not connected to `SBoardCredentialProviderService` or `PasskeySelectionActivity`. It does not yet parse Android `CreatePublicKeyCredentialRequest` into this normalized JSON input, enforce `excludeCredentials` against the database, verify calling-app origin / Digital Asset Links, perform biometric confirmation, or return the final Android Credential Manager create response. Its origin parameter is a security-sensitive trusted input, not something to accept from an arbitrary UI field. Added JVM tests for output shape and rejection cases; tests have not been executed here.


## WebAuthn origin/RP-ID hardening — development stage

- Added shared `WebAuthnOriginPolicy` used by both registration and assertion engines.
- Normalizes DNS RP IDs with IDN/STD3 rules and rejects malformed/single-label/IP-literal RP IDs.
- Requires an HTTPS origin host to be exactly the RP ID or a proper subdomain; rejects lookalike suffixes, unrelated domains, credentials, paths, query/fragment and explicit non-default ports.
- Added JVM tests for valid RP host/subdomain cases and invalid/unrelated origins.

Important boundary: this conservative relationship check is **not** a Public Suffix List implementation and does not establish app/site ownership. Before enabling either ceremony, the Android integration must validate that the RP ID is not a public suffix and must verify native-app Digital Asset Links / trusted calling-app identity. These engines still accept `origin` only as a trusted input supplied by that integration.

## Biometric authorization gate (development stage)

- Added `PasskeyAuthorization`: a credential-bound, 30-second, single-use authorization grant.
- `PasskeyRepository.openPrivateKey` now requires that grant and consumes it before reading/decrypting the selected private key. Calls without a grant, for another credential, after expiry, or after prior use are rejected.
- `BiometricVaultManager.authorizePasskeyAfterBiometric` completes the existing Android Keystore AES-GCM unwrap using the `Cipher` returned by a successful `BiometricPrompt` callback, then issues the grant for that one credential.
- Intended caller flow: prepare the unlock cipher -> show `BiometricPrompt` with `CryptoObject(cipher)` -> on success call `authorizePasskeyAfterBiometric` -> pass the returned grant to `openPrivateKey` -> wipe returned PKCS#8 bytes after signing.
- This is an API/security boundary stage only. It is not yet wired into `PasskeySelectionActivity`; that activity remains fail-closed. The Vault key remains resident in `VaultSession` until explicitly locked, so this does not claim per-operation key isolation after the first biometric unlock.


## Assertion pipeline orchestration — development stage

- Added `PasskeyAssertionPipeline` to connect selected-record metadata, the one-time biometric authorization gate, encrypted private-key retrieval, ES256 PKCS#8 decoding, WebAuthn assertion construction, and usage-counter persistence.
- The pipeline checks a non-empty `allowCredentials` list before opening the private key; malformed or mismatching entries are rejected.
- Private-key byte arrays are wiped in a `finally` block after signing. Usage metadata is updated only after assertion construction succeeds.
- `PasskeyRepository` now exposes a metadata-only lookup for the selected record and uses the shared RP ID normalization policy.
- Added JVM tests for a signed assertion and rejection of credentials outside `allowCredentials`.

**Still not connected to the Android selection activity.** The activity remains fail-closed. The remaining Android integration must retrieve the `ProviderGetCredentialRequest`, obtain a system-supplied trusted origin / calling-app identity, show a real `BiometricPrompt` with the Keystore-backed cipher, call this pipeline, and return the Credential Manager response. Do not enable the provider as production-ready.

## Android calling-app origin foundation

- Added `CallingAppOriginResolver`, which derives direct Android-app origins from the SHA-256 digest of the calling package's signing certificate (`android:apk-key-hash:` form).
- Browser-delegated origins are accepted only through `CallingAppInfo.getOrigin()` with an explicit privileged-caller allowlist. The development allowlist is intentionally empty, so browser delegation fails closed until a reviewed browser package and release certificate fingerprint are configured.
- Multi-signer packages are rejected by this development resolver rather than selecting an ambiguous certificate.
- Added JVM tests for the pure Android-origin formatter.
- This resolver is not yet wired into `PasskeySelectionActivity` or the assertion pipeline. Android-app origin support, Digital Asset Links / Related Origin Requests, and the final Credential Manager response remain disabled. The current WebAuthn engines still accept only HTTPS web origins; do not enable signing based on this foundation alone.

## Digital Asset Links verification foundation

- Added a bounded HTTPS fetcher for `https://<rpId>/.well-known/assetlinks.json`; HTTP redirects are disabled and response bodies are capped at 256 KiB.
- Added strict matching for the `delegate_permission/common.get_login_creds` relation, exact Android package name, and SHA-256 signing-certificate fingerprint.
- Updated `CallingAppOriginResolver` so a direct Android calling app receives an `android:apk-key-hash:` origin only after the RP's Digital Asset Links statement matches the calling package and signing certificate.
- Browser-delegated HTTPS origins remain unavailable until a reviewed package/certificate allowlist is configured; the development allowlist is empty.
- Added pure JVM matcher tests for matching relation/package/fingerprint and rejection of mismatches.

This is a verification foundation, not yet an enabled sign-in flow. The resolver is not yet called from `PasskeySelectionActivity`; the WebAuthn assertion engine still accepts HTTPS web origins only, and the Credential Manager response is not wired. Public Suffix List checks, Related Origin Requests, signing-certificate rotation policy, and real-device validation remain outstanding. Network verification has not been executed against a live RP from this build environment.

## Trusted assertion coordination stage

Added `TrustedPasskeyAssertionCoordinator` as the explicit composition boundary between
Credential Manager calling-app identity, the verified-origin resolver, and the existing
biometric-gated assertion pipeline. `CallingAppTrustedOriginProvider` adapts the AndroidX
`CallingAppInfo` captured from the provider request. The coordinator normalizes the RP ID,
resolves the origin only through that trusted identity adapter, validates the origin/RP pairing,
and only then invokes the assertion pipeline. The pipeline remains responsible for consuming
single-use biometric authorization before decrypting key material.

This is an integration boundary, not yet the final Activity-to-Credential-Manager response flow.
`PasskeySelectionActivity` remains fail-closed; no assertion is returned to the relying party yet.

## Biometric selection activity integration (development stage)

`PasskeySelectionActivity` now extends `FragmentActivity` and has a first-pass interactive get-assertion path:

- retrieves `ProviderGetCredentialRequest` from the system-supplied PendingIntent;
- accepts exactly one `GetPublicKeyCredentialOption` and uses its original `requestJson`;
- rejects non-null `clientDataHash` requests (delegated browser path remains disabled);
- obtains a per-operation Android Keystore AES-GCM cipher from `BiometricVaultManager`;
- presents `BiometricPrompt` with the actual `CryptoObject` cipher;
- only after biometric success, unlocks the Vault session and issues one-shot `PasskeyAuthorization`;
- resolves trusted caller Origin through `CallingAppTrustedOriginProvider`, performs the assertion pipeline, and returns a `PublicKeyCredential` through `PendingIntentHandler`.

This is source-level integration only. It has not been built or exercised on an Android 14+ device. In particular, Digital Asset Links verification, Credential Manager parceling, Keystore biometric behavior, and the final framework response still require device testing. At this stage discovery was still gated on an unlocked Vault; the latest locked-Vault discovery section below supersedes that limitation. Browser-delegated origins and `clientDataHash` are deliberately rejected. Do not treat this as production-ready.

## Latest stage — locked-Vault discovery (2026-10-31)

- Credential Provider discovery no longer requires `VaultSession.isUnlocked`. It can enumerate matching Passkey entries after process restart while the Vault remains locked.
- Replaced the discovery query's `SELECT *` with a narrow Room projection containing only record ID, RP ID/name, user/display names, credential ID, and timestamps. The query does **not** load `encryptedPrivateKey`, IV, or `publicKeyCose`.
- Private-key decryption remains in the selected-credential path, after `BiometricPrompt` succeeds and `BiometricVaultManager.finishUnlock` restores the Vault key into the in-memory session.
- Added JVM tests for allowCredentials matching and a structural assertion that the discovery projection excludes key/ciphertext columns.
- An explicitly empty/malformed non-empty allowlist fails closed; an omitted or zero-length `allowCredentials` list remains a discoverable-credential request.

Security/privacy note: credential labels, RP ID, and credential IDs remain in the local Room database in plaintext to support locked discovery. The private key remains AES-GCM encrypted. Encrypting all discovery metadata at rest would require a separate device-bound index/key design and is not claimed here.

Verification: source changes and test cases are included. Full Gradle compilation, Room KAPT/schema validation, JUnit execution, and Android 14+ device testing have not been run in this environment. This stage does not establish production readiness.

## 2026-10-31 — encrypted Passkey discovery metadata

- Added a separate `passkey_secure_metadata` table. New Passkeys store RP ID, RP/site label, username, display name, user handle, and COSE public key inside an AES-256-GCM envelope protected by a non-exportable Android Keystore key.
- The main Passkey row stores a keyed HMAC-SHA-256 blind index of the normalized RP ID instead of the domain name; discovery computes the same index without unlocking the Vault.
- The credential ID remains in the main table because Credential Manager needs it to match `allowCredentials` and construct the selected credential entry. It is a public WebAuthn identifier, not private-key material.
- Added a v4 -> v5 Room migration and lazy conversion of legacy plaintext metadata when a record is next discovered or read. The conversion writes the encrypted envelope and scrubs legacy label/user-handle/public-key columns.
- Added an integrity check that verifies the decrypted RP ID hashes back to the stored blind index before exposing a discovery entry or assertion metadata.
- The private key remains separately encrypted under the Vault key. Metadata Keystore keys are not biometric-gated; their purpose is protection against offline database extraction while allowing the system picker to display labels with the Vault locked. They do not protect against code already executing with the app's UID.

Verification boundary: the new Android Keystore code, Room migration/KAPT, full Gradle build, and device behavior have not been run in this environment. Legacy conversion is lazy and should be exercised with upgrade fixtures before release. No APK was produced.

## Passkey integrity audit and recovery guidance (development stage)

- Added a read-only `PasskeyRepository.auditIntegrity()` report for secure metadata, legacy rows,
  missing secure envelopes, metadata authentication failures, HMAC-index mismatches, malformed
  credential material, unavailable/invalidated Android Keystore keys, and orphaned metadata rows.
- Added pure policy checks for blind-index shape and minimum credential-material invariants.
- The audit never deletes or rewrites records. Corrupt or undecryptable records remain unavailable
  to discovery/signing (fail closed); the user must restore from a known-good encrypted backup or
  re-register the affected Passkey after investigating the cause.
- A lost/invalidated metadata Keystore key cannot be reconstructed from the SQLite database.
  Restoring the database alone is insufficient; use an encrypted backup that includes the required
  key-recovery material if supported, otherwise re-enrollment is required. No automatic key reset
  is performed because that would make existing ciphertext permanently unreadable.
- This is an audit/reporting layer, not automatic repair or a guarantee against filesystem/database
  rollback. Credential ID remains visible for Credential Manager matching. Full Android Keystore,
  Room, Gradle, backup-restore and device tests remain outstanding.

The audit uses a SQL length-only projection for credential ciphertext invariants, so it does not load
private-key ciphertext into application memory during the audit. Findings carry a non-destructive
recommended action: migrate legacy rows, restore/re-register damaged credentials, or investigate
orphan metadata without automatic deletion. The audit also checks user-handle and COSE-public-key
presence after metadata decryption.

## Registration request validation (latest development increment)

`WebAuthnRegistrationEngine` now calls `WebAuthnRegistrationPolicy` before generating any key material. The policy validates the supported WebAuthn creation-request subset and returns parsed RP/user metadata plus excluded credential IDs. Unsupported extensions and non-platform attachment requirements fail closed. This does not yet connect `onBeginCreateCredentialRequest` to an Android creation activity; provider registration remains unavailable until that ceremony and its AndroidX response hand-off are implemented and device-tested.


## Explicit backup-version deletion (latest increment)

- Added an explicit delete action for a selected encrypted Google Drive snapshot.
- The UI asks for confirmation before deletion.
- The Drive client re-lists versions before deleting and refuses to delete the last visible recovery point.
- After deletion, the history is refreshed. No automatic retention deletion is enabled.
- Added a unit test for the last-backup deletion guard.
- Android Gradle build, JUnit suite and live Drive API behavior remain unverified in this environment.

## Backup restore orchestration increment
- Added an explicit Google Drive restore coordinator for versioned snapshots.
- Selection is revalidated against a fresh Drive listing before downloading.
- Existing encrypted restore pipeline performs authenticated decryption, SQLite integrity/schema checks, and rollback-protected replacement.
- UI, fresh-install onboarding, and device-level verification are still pending.

## App startup / first-run wizard increment

A new-install setup gate now precedes the main navigation until a master password and Recovery Key are configured and the user acknowledges offline storage. Recovery Key and password UI values are intentionally held in non-saveable Compose state. Existing installations are not silently assigned a new Recovery Key. Google Drive OAuth is still a separate post-setup Profile action and requires end-to-end validation.


## 2026-10-31 — portable Passkey metadata (schema v8)

- Added `passkey_portable_metadata`, a new Room table containing an AES-256-GCM envelope encrypted with the same random Vault Key used for the Passkey private key. Record ID is authenticated as AAD. This metadata includes RP ID, RP/site labels, user handle, and COSE public key.
- Kept `passkey_secure_metadata` as a device-local, Android Keystore-encrypted discovery cache. It is rebuildable and is no longer the only copy of the metadata. The local HMAC RP index is regenerated on each device.
- Added Room migration 7→8 and restore preflight checks for the new table, IV/ciphertext shape, and orphan rows.
- New Passkey writes persist the record, local cache, and portable envelope in one Room transaction. Existing v5-v7 records can be upgraded to portable metadata on the original device after the Vault is unlocked.
- After a database restore on another phone, master-password, Recovery Key, and biometric unlock flows attempt `rebuildDeviceDiscoveryCache()` automatically. The routine decrypts portable metadata with the restored Vault Key and creates that phone's own Keystore cache. A damaged individual Passkey is skipped without blocking Vault unlock or other entries. Subsequent locked discovery can use the rebuilt cache. Until the first successful unlock/cache rebuild, Credential Provider discovery on the new device may return no entries while the Vault is locked.
- Full encrypted database backups include the new table. This increment does not yet add Passkeys to the per-record incremental sync journal; cross-device propagation currently depends on the full encrypted database backup/restore path.
- Verification performed: standalone Kotlin compilation of the portable AES-GCM cipher; SQLite table smoke test; source inspection of migration/restore validator; ZIP integrity check. Android Gradle/KAPT/JUnit, Room migration execution, Keystore behavior, real Google Drive restore, and two-device Credential Manager flows remain unverified.
