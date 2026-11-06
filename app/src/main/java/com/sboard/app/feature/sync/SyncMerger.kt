package com.sboard.app.feature.sync

import com.sboard.app.core.crypto.B64
import com.sboard.app.data.ItemCipher
import com.sboard.app.data.VaultItemEntity

/**
 * Three-way, per-item merge between this device and the cloud copy.
 * The common ancestor of each item is identified by [VaultItemEntity.baseHash] — the encrypted
 * version last confirmed in Google Drive. Rules:
 *  - changed on one side only → that side wins;
 *  - changed on both sides → conflict: an edit beats a deletion, otherwise the newer edit wins
 *    (ties broken by device id); the losing edit is preserved in the item's history;
 *  - tombstones older than [TOMBSTONE_TTL_MS] are dropped from the cloud copy.
 */
object SyncMerger {
    const val TOMBSTONE_TTL_MS = 180L * 24 * 60 * 60 * 1000

    data class Result(
        /** Final state of every item for the local database, with baseHash already set for items equal to the cloud. */
        val localUpserts: List<VaultItemEntity>,
        /** Local versions that lost a conflict and should be preserved in history. */
        val conflictLosers: List<VaultItemEntity>,
        /** Records that the merged cloud file must contain. */
        val upload: List<SyncRecord>,
        /** True when the merged set differs from what the cloud currently holds. */
        val cloudChanged: Boolean,
        val pulled: Int,
        val pushed: Int,
        val conflicts: Int,
    )

    fun toRecord(e: VaultItemEntity) = SyncRecord(e.id, e.createdAt, e.updatedAt, e.deleted, e.modifiedBy, B64.std(e.iv), B64.std(e.ciphertext))

    fun toEntity(r: SyncRecord, baseHash: String?): VaultItemEntity {
        val iv = B64.fromStd(r.iv)
        val ct = B64.fromStd(r.ct)
        val hash = ItemCipher.contentHash(iv, ct)
        return VaultItemEntity(r.id, iv, ct, r.createdAt, r.updatedAt, r.deleted, r.modifiedBy, hash, baseHash)
    }

    fun recordHash(r: SyncRecord): String = ItemCipher.contentHash(B64.fromStd(r.iv), B64.fromStd(r.ct))

    fun merge(local: List<VaultItemEntity>, remote: List<SyncRecord>, now: Long = System.currentTimeMillis()): Result {
        val localById = local.associateBy { it.id }
        val remoteById = remote.associateBy { it.id }
        val upserts = ArrayList<VaultItemEntity>()
        val losers = ArrayList<VaultItemEntity>()
        val finalCloud = LinkedHashMap<String, SyncRecord>()
        var pulled = 0; var pushed = 0; var conflicts = 0

        for (id in (localById.keys + remoteById.keys)) {
            val l = localById[id]
            val r = remoteById[id]
            when {
                l == null && r != null -> {
                    upserts += toEntity(r, recordHash(r)); finalCloud[id] = r; pulled++
                }
                l != null && r == null -> {
                    finalCloud[id] = toRecord(l); pushed++
                }
                l != null && r != null -> {
                    val hR = recordHash(r)
                    val hL = l.contentHash
                    if (hL == hR) {
                        finalCloud[id] = r
                        if (l.baseHash != hL) upserts += l.copy(baseHash = hL)
                        continue
                    }
                    val localChanged = l.baseHash != hL
                    val remoteChanged = l.baseHash != hR
                    when {
                        !localChanged -> { upserts += toEntity(r, hR); finalCloud[id] = r; pulled++ }
                        !remoteChanged -> { finalCloud[id] = toRecord(l); pushed++ }
                        else -> {
                            conflicts++
                            val remoteWins = when {
                                l.deleted != r.deleted -> l.deleted // an edit beats a deletion
                                l.updatedAt != r.updatedAt -> r.updatedAt > l.updatedAt
                                else -> r.modifiedBy > l.modifiedBy
                            }
                            if (remoteWins) {
                                if (!l.deleted) losers += l
                                upserts += toEntity(r, hR); finalCloud[id] = r; pulled++
                            } else {
                                finalCloud[id] = toRecord(l); pushed++
                            }
                        }
                    }
                }
            }
        }
        val cutoff = now - TOMBSTONE_TTL_MS
        finalCloud.values.removeAll { it.deleted && it.updatedAt < cutoff }
        val cloudChanged = finalCloud.size != remote.size || finalCloud.any { (id, rec) -> remoteById[id] != rec }
        return Result(upserts, losers, finalCloud.values.toList(), cloudChanged, pulled, pushed, conflicts)
    }

    /** Updates this device's entry and keeps revocations from other devices. */
    fun mergeDevices(remote: List<SyncDevice>, selfId: String, selfName: String, now: Long): List<SyncDevice> {
        val others = remote.filter { it.id != selfId }
        val self = remote.firstOrNull { it.id == selfId }
        return others + SyncDevice(selfId, selfName, now, revoked = self?.revoked ?: false)
    }
}
