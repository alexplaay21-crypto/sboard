package com.sboard.app.feature.sync

import com.sboard.app.core.crypto.randomBytes
import com.sboard.app.data.ItemCipher
import com.sboard.app.data.ItemType
import com.sboard.app.data.VaultItemEntity
import com.sboard.app.data.VaultItemPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergerTest {
    private val key = randomBytes(32)
    private fun item(id: String, title: String, at: Long, device: String = "A", deleted: Boolean = false, base: String? = null) =
        ItemCipher.seal(key, id, 1, at, deleted, device, if (deleted) null else VaultItemPayload(ItemType.NOTE, title), base)
    private fun synced(e: VaultItemEntity) = e.copy(baseHash = e.contentHash)
    private fun rec(e: VaultItemEntity) = SyncMerger.toRecord(e)

    @Test fun pullsNewRemoteItems() {
        val remote = item("r", "Remote", 10, "B")
        val result = SyncMerger.merge(emptyList(), listOf(rec(remote)), now = 1000)
        assertEquals(1, result.pulled)
        assertEquals(remote.contentHash, result.localUpserts.single().baseHash)
        assertFalse(result.cloudChanged)
    }

    @Test fun pushesNewLocalItems() {
        val local = item("l", "Local", 10)
        val result = SyncMerger.merge(listOf(local), emptyList(), now = 1000)
        assertEquals(1, result.pushed)
        assertTrue(result.cloudChanged)
        assertEquals("l", result.upload.single().id)
    }

    @Test fun remoteEditWinsWhenLocalUnchanged() {
        val base = synced(item("x", "v1", 10))
        val remote = item("x", "v2", 20, "B")
        val result = SyncMerger.merge(listOf(base), listOf(rec(remote)), now = 1000)
        assertEquals(remote.contentHash, result.localUpserts.single().contentHash)
        assertEquals(0, result.conflicts)
        assertFalse(result.cloudChanged)
    }

    @Test fun localEditIsPushedWhenRemoteUnchanged() {
        val base = synced(item("x", "v1", 10))
        val local = item("x", "v2", 20, base = base.contentHash)
        val result = SyncMerger.merge(listOf(local), listOf(rec(base)), now = 1000)
        assertTrue(result.cloudChanged)
        assertEquals(local.contentHash, SyncMerger.recordHash(result.upload.single()))
    }

    @Test fun concurrentEditsResolveToNewestAndKeepLoser() {
        val base = synced(item("x", "v1", 10))
        val local = item("x", "local", 30, "A", base = base.contentHash)
        val remote = item("x", "remote", 20, "B")
        val result = SyncMerger.merge(listOf(local), listOf(rec(remote)), now = 1000)
        assertEquals(1, result.conflicts)
        assertTrue(result.conflictLosers.isEmpty()) // local (newer) won; remote edit is replaced in the cloud
        assertEquals(local.contentHash, SyncMerger.recordHash(result.upload.single()))

        val older = item("x", "local-old", 15, "A", base = base.contentHash)
        val r2 = SyncMerger.merge(listOf(older), listOf(rec(remote)), now = 1000)
        assertEquals(older.id, r2.conflictLosers.single().id)
        assertEquals(remote.contentHash, r2.localUpserts.single().contentHash)
    }

    @Test fun editBeatsConcurrentDeletion() {
        val base = synced(item("x", "v1", 10))
        val localEdit = item("x", "edited", 20, "A", base = base.contentHash)
        val remoteDelete = item("x", "", 30, "B", deleted = true)
        val result = SyncMerger.merge(listOf(localEdit), listOf(rec(remoteDelete)), now = 1000)
        assertFalse(result.upload.single().deleted)
    }

    @Test fun deletionPropagates() {
        val base = synced(item("x", "v1", 10))
        val remoteDelete = item("x", "", 30, "B", deleted = true)
        val result = SyncMerger.merge(listOf(base), listOf(rec(remoteDelete)), now = 1000)
        assertTrue(result.localUpserts.single().deleted)
    }

    @Test fun identicalStateIsNoOp() {
        val s = synced(item("x", "v1", 10))
        val result = SyncMerger.merge(listOf(s), listOf(rec(s)), now = 1000)
        assertFalse(result.cloudChanged)
        assertTrue(result.localUpserts.isEmpty())
    }

    @Test fun oldTombstonesAreDropped() {
        val now = SyncMerger.TOMBSTONE_TTL_MS * 2
        val tomb = synced(item("x", "", 5, deleted = true))
        val result = SyncMerger.merge(listOf(tomb), listOf(rec(tomb)), now = now)
        assertTrue(result.upload.isEmpty())
        assertTrue(result.cloudChanged)
    }

    @Test fun devicesKeepRevocation() {
        val devices = SyncMerger.mergeDevices(listOf(SyncDevice("A", "Old", 1, revoked = true), SyncDevice("B", "Tab", 2)), "A", "New", 9)
        assertTrue(devices.first { it.id == "A" }.revoked)
        assertEquals(9, devices.first { it.id == "A" }.lastSyncAt)
    }
}
