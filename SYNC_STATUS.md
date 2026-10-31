# sBoard multi-device sync status

## Current increment: local change journal and safe conflict decisions

Implemented in this source snapshot:

- Room schema version 6 adds `sync_change_journal`.
- Vault record creation/update and deletion append a journal event in the same Room transaction as the record mutation.
- Journal rows contain only a record ID, operation (`UPSERT`/`DELETE`), local installation UUID, monotonically increasing per-installation counter, and timestamp.
- Installation identity and counter are persisted in app-private preferences. The counter API supports advancing after a future remote revision is observed.
- `SyncConflictPolicy` refuses automatic last-write-wins for different content coming from different devices. It reports a conflict so a future UI can ask the user which version to keep.

## Not implemented yet

This is **not live multi-device synchronization**. Current Google Drive integration still uploads encrypted whole-database snapshots. The following are still required:

1. Encrypted per-record change envelopes and a Drive manifest/change-feed format.
2. Remote journal download, validation, deduplication and acknowledgement.
3. Tombstone retention and safe deletion propagation.
4. Passkey, OTP, identity/card and security-setting change journal integration.
5. Conflict-resolution screen with side-by-side metadata and safe merge/keep-both options.
6. Device pairing/list/revoke management.
7. End-to-end tests with two Android devices and concurrent edits.

Do not describe the current feature as bidirectional sync or claim that edits made on one device already appear on another.

## Verification

- The pure Kotlin conflict policy compiles with `kotlinc`.
- The v5→v6 table/index SQL was executed against SQLite successfully.
- Full Android Gradle/KAPT build, Room migration test and JUnit suite were not run in this environment.

## Latest increment: authenticated encrypted change envelope

Added `EncryptedSyncEnvelope`, a bounded and versioned binary transport format for one journal event. It encrypts both journal metadata and the opaque record payload with AES-256-GCM. The envelope key is derived from the existing 256-bit Vault key using HMAC-SHA-256 with a domain-separated context string; the nonce is freshly generated for each envelope, and a fixed format/version header is authenticated as AAD. An UPSERT must carry the existing at-rest row IV+ciphertext; a DELETE is represented as a payload-free tombstone. Decoding rejects unknown versions, malformed lengths, oversized payloads, trailing bytes, invalid operations and authentication failures.

The payload does not contain plaintext Vault fields. This is a transport primitive only: it is not yet uploaded to Drive, does not define a remote manifest/change feed, does not acknowledge or deduplicate remote events, and does not prevent replay by itself. Both devices must already possess the same Vault key through a separately authenticated pairing/restore flow. Passkeys and other non-Vault-record entities are not included yet.

Verification: the new source compiles with the standalone Kotlin compiler. A JVM smoke harness passed UPSERT round-trip, metadata confidentiality, DELETE tombstone, tamper rejection and wrong-key rejection. JUnit tests were added but the full JUnit/Gradle suite and Android build were not run.


## Latest increment: Drive exchange coordinator and encrypted inbox (development)

- Room schema advances from version 6 to 7. The local journal now tracks delivery state (`PENDING`, `SENT`, `SUPERSEDED`); an encrypted `sync_inbox` stores authenticated incoming envelopes for review.
- `GoogleDriveVaultSyncCoordinator` compacts pending local events to the latest state per record, wraps the existing at-rest encrypted row (or a tombstone) in `EncryptedSyncEnvelope`, uploads it idempotently, and marks it sent only after Drive confirms upload.
- The coordinator lists remote change files, downloads and authenticates each envelope, checks that the authenticated change ID matches the Drive filename, ignores changes from this installation, and stages other-device envelopes encrypted in Room. Duplicate inbox events are ignored.
- Incoming data is deliberately **not applied automatically**. It remains encrypted in `sync_inbox` until a conflict-resolution/apply flow can validate local state and avoid overwriting concurrent edits. This is a one-way exchange foundation, not yet a complete bidirectional Vault sync.
- Added pure tests for latest-change compaction, sent-event exclusion, and own-device filtering.

Verification: pure `SyncExchangePolicy.kt` compiles with the standalone Kotlin compiler. The Room migration SQL and full Android/Gradle/KAPT/JUnit suite were not run; live Google OAuth/Drive behavior and two-device scenarios remain unverified.

- A randomized encrypted envelope is now cached in the local journal before network upload. If the process dies after Drive accepted the upload but before the local `SENT` flag is committed, the next attempt reuses the exact same bytes; this avoids false idempotency failures caused by a fresh AES-GCM nonce.

## Latest increment: explicit inbox resolution and convergence event

- Added `VaultSyncInboxManager` to expose pending incoming changes as non-secret review metadata and resolve an item with an explicit `ACCEPT_REMOTE` or `KEEP_LOCAL` decision.
- Before an incoming UPSERT can be applied, the manager authenticates the outer sync envelope, checks all staged metadata against the authenticated payload, splits the existing encrypted row blob, and verifies the inner record AES-GCM tag using the unlocked Vault key and record-ID AAD.
- Applying or keeping a version is performed in a Room transaction with the inbox state transition. The manager appends a fresh local journal event representing the selected final state; this publishes the user's conflict decision to other devices instead of echoing the original remote event.
- The inbox API exposes only IDs, operation, device, revision and timestamps to the review layer; encrypted payload buffers are cleared after use.
- Added pure policy tests for resolution-state mapping and UPSERT/DELETE convergence events.

This is a resolution engine, not yet a finished conflict-resolution screen. Incoming events still require explicit user choice; no automatic last-write-wins is performed. The event transport must be scheduled and OAuth token acquisition wired into the foreground/unlocked session before this becomes seamless live sync. Full Android/Room/JUnit build and real two-device Drive testing remain unverified.

## 2026-10-31 — event-driven sync scheduling

Added `SyncAutomationScheduler`, `SyncAutomationWorker`, and `GoogleDriveSyncAutomationExecutor`. A committed local sync-journal mutation schedules a one-time Drive exchange with a 3-second debounce; app foreground and Vault unlock request an immediate catch-up pass. The worker obtains the short-lived Google Identity token at execution time and only proceeds while the in-memory Vault session is unlocked. No OAuth token or Vault key is written to WorkManager data, and no daily timer is used.

This is an event-driven transport trigger, not yet fully automatic bidirectional Vault convergence: incoming changes remain encrypted in the inbox for review, and the worker cannot sync while the Vault is locked or interactive OAuth consent is required. Full Android build, Room observer, WorkManager, OAuth and multi-device tests remain outstanding.

## 2026-10-31 — in-app sync review controls

Added a Vault review panel for authenticated incoming changes. It lists pending remote updates/deletions using non-secret metadata only (record ID, device identifier prefix, operation and timestamp), and offers explicit **Keep mine** / **Accept from cloud** actions. The panel is height-bounded and independently scrollable so it does not consume the entire Vault screen. Accepting a remote change refreshes the visible Vault records; either decision refreshes the pending inbox. Added RU/EN strings.

This connects the existing inbox resolution engine to a user-facing decision surface. It does not add automatic conflict merging, and the incoming changes remain intentionally user-reviewed. Full Android/Compose/Room build and device testing could not be run in this environment because the project has no Gradle wrapper and no system Gradle installation.

## 2026-10-31 — conservative automatic application of non-conflicting changes

Added `VaultSyncInboxManager.applyNonConflicting()`. When the Vault opens, it attempts to accept authenticated incoming changes only when the target record ID is absent locally. The absence check is repeated inside the same Room transaction that applies the remote state and marks the inbox item resolved, so a concurrent local insertion cannot be silently overwritten. Existing local records are never auto-replaced or auto-deleted; they remain in the explicit review panel. A successfully accepted state is published as a fresh local journal event through the existing resolution path.

This is intentionally conservative: it does not infer that an existing record is unchanged, and it does not auto-merge edits. The app must be unlocked to process the inbox. Full Android/Room/Compose build and two-device Google Drive tests remain unverified.
