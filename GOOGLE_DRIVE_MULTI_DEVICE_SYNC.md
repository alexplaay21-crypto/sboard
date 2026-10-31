# sBoard multi-device sync over Google Drive

## Intended user experience

The same person signs into the same Google account in sBoard on each phone. On first connection, sBoard reads the app's encrypted sync data from Google Drive, verifies/decrypts it locally with the same Vault key (recovered through the Recovery Key or a secure device-to-device setup), and populates the local Vault. A new or edited record is journaled locally and queued for upload. Another device downloads unseen changes, verifies the authenticated envelope, and applies them locally. Deletions are represented by encrypted tombstones.

This is application-managed synchronization, not Google automatically merging the database. sBoard must explicitly list, download, authenticate, reconcile, and apply remote changes.

## Google Drive storage choice

The current implementation uses Drive API v3 `appDataFolder` and the narrow `https://www.googleapis.com/auth/drive.appdata` scope. It is a hidden, app-specific area, not a normal folder visible in Drive UI. The same sBoard app identity can access the same user's app data from the user's other devices after that user grants the same scope. The folder cannot be shared with other users or other apps. If users must see/manage the files in Drive UI, use a visible app-owned folder with a suitable user-visible Drive scope instead; that is a separate OAuth/storage design.

All devices must use the same production sBoard application identity / Google OAuth configuration and the same Google account. Signing into a different Google account must never silently expose or merge another account's Vault.

## Current implementation boundary (development build)

- Whole-database encrypted snapshots remain the backup/restore mechanism.
- `SyncChangeEntity` is the local non-secret change journal.
- `EncryptedSyncEnvelope` authenticates and encrypts an individual change.
- `GoogleDriveSyncTransport` lists, uploads and downloads encrypted envelope files in `appDataFolder`.
- `GoogleDriveVaultSyncCoordinator` now builds outgoing envelopes from the latest pending journal state and encrypted Room row, uploads them, and marks successful events as sent.
- The coordinator downloads remote envelopes, authenticates them, checks filename/change-ID binding, ignores events from the current installation and events already present in the restored local change journal, and stores genuinely unseen other-device envelopes encrypted in `sync_inbox` for review.
- Randomized AES-GCM envelopes are cached in the local outbox before upload so a retry after process death reuses identical bytes.

**This is still not completed bidirectional Vault sync.** A replacement device is seeded through the explicit encrypted whole-database restore screen (Google account authorization + the offline Recovery Key); the per-record feed then carries later edits. Incoming unseen changes are staged encrypted; non-conflicting records can be accepted by the Vault inbox workflow and conflicting records require an explicit choice. The worker is scheduled after local journal changes and when the app returns to foreground, but requires an unlocked Vault and a silently available short-lived OAuth token. The coordinator is not an always-on daemon. The restored database's historical journal is checked by change ID so old feed events already represented in the snapshot are not re-imported as new edits on the replacement installation. Full automatic multi-device convergence, remote acknowledgements/tombstone compaction, passkey change replication, and end-to-end verification remain unfinished.

## Security and recovery requirements

- Never upload plaintext Vault fields or raw master/Recovery Keys.
- Every envelope must authenticate before its content is used.
- Do not apply a remote change if its Vault key/account identity does not match the currently unlocked Vault.
- Keep remote deletions as tombstones until all active devices have acknowledged them or a safe retention policy expires them.
- Resolve concurrent edits explicitly; do not rely on Drive modifiedTime as a conflict-resolution authority.
- Whole-database backups remain available as recovery points independent of the change-feed transport.

## 2026-10-31 — replacement-device replay protection

- During incoming Drive sync, the coordinator now checks the restored local change journal by authenticated `changeId` before staging a remote event. This is important because a replacement installation has a new local device ID even though its restored snapshot already contains historical journal rows from the original installation.
- Historical events already included in the restored snapshot are counted separately as `alreadyKnownChanges`; they are not placed into the review inbox again. Events from the current installation remain separately counted as own changes.
- Added a JVM policy test for a restored journal suppressing a replayed Drive event while allowing an unseen event from the old device.
- Verification limitation: no Gradle wrapper or installed Gradle executable is available in this environment. The test source was added but not executed; Android Room/Compose build, live Drive and two-device behavior remain unverified.
