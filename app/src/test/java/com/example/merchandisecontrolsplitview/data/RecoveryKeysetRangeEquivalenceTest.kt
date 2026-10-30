package com.example.merchandisecontrolsplitview.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual in-memory Room DAO output versus the original nullable-OR SQL, including malformed IDs.
 * No product repository, auth, cloud, existing app database or query-plan performance claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecoveryKeysetRangeEquivalenceTest {
    private lateinit var db: AppDatabase
    private val manifestRows = linkedMapOf<Triple<String,String,String>,SyncRecoveryManifestRow>()
    private val historyRows = linkedMapOf<Long,HistoryRecoveryPhysicalRow>()

    @Before fun setup() {
        val app: Application = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app,AppDatabase::class.java).allowMainThreadQueries().build()
        manifestRows.clear(); historyRows.clear()
    }
    @After fun teardown() { if (::db.isInitialized) db.close() }

    private suspend fun insertManifest(rows: List<SyncRecoveryManifestRow>) {
        db.syncRecoveryManifestDao().insertAll(rows)
        rows.forEach { manifestRows[Triple(it.generationId,it.domain,it.remoteId)] = it }
    }
    private suspend fun insertHistory(ids: List<String>, mixed: Boolean) {
        ids.forEachIndexed { index,id ->
            val entry = HistoryEntry(id="synthetic-file-$index",displayName="Synthetic $index",
                timestamp="2026-10-01 10:00:00",data=listOf(listOf("synthetic",index.toString())),
                editable=listOf(listOf("1","0")),complete=listOf(index % 2 == 0),supplier="Supplier $index",
                category="Category $index",wasExported=index % 2 == 0,
                syncStatus=if(mixed && index % 5 == 0) SyncStatus.NOT_ATTEMPTED else SyncStatus.SYNCED_SUCCESSFULLY,
                orderTotal=index.toDouble(),paymentTotal=index/2.0,missingItems=index,totalItems=index+1,
                isManualEntry=index % 2 != 0,deletedAt=if(mixed && index % 3 == 0) "2026-10-02T10:00:00Z" else null)
            val uid = db.historyEntryDao().insert(entry)
            val localRevision = if(mixed && index % 4 == 0) 2 else 1
            // Index2 is live, synced, revision-clean and fingerprinted; only appliedAt is missing.
            val applied = if(mixed && (index % 6 == 0 || index == 2)) null else 1000L
            val fingerprint = if(mixed && index % 7 == 0) null else "synthetic-body-$index"
            db.historyEntryRemoteRefDao().insert(HistoryEntryRemoteRef(historyEntryUid=uid,remoteId=id,
                localChangeRevision=localRevision,lastSyncedLocalRevision=1,
                lastRemoteAppliedAt=applied,lastRemotePayloadFingerprint=fingerprint))
            historyRows[uid] = HistoryRecoveryPhysicalRow(entry.copy(uid=uid),id,localRevision,1,fingerprint)
        }
    }
    private fun originalManifest(generation: String, domain: String, after: String?, limit: Int,
        activeOnly: Boolean): List<SyncRecoveryManifestRow> {
        val active = if(activeOnly) " AND active = 1" else ""
        val sql = "SELECT * FROM sync_recovery_manifest WHERE generationId = ? AND domain = ?$active " +
            "AND (? IS NULL OR remoteId > ?) ORDER BY remoteId LIMIT ?"
        return db.openHelper.readableDatabase.query(sql,arrayOf<Any?>(generation,domain,after,after,limit)).use { cursor ->
            buildList {
                while(cursor.moveToNext()) add(requireNotNull(manifestRows[Triple(
                    cursor.getString(cursor.getColumnIndexOrThrow("generationId")),
                    cursor.getString(cursor.getColumnIndexOrThrow("domain")),
                    cursor.getString(cursor.getColumnIndexOrThrow("remoteId")))]))
            }
        }
    }
    private fun originalHistory(after: String?, limit: Int, onlyClean: Boolean,
        activeOnly: Boolean): List<HistoryRecoveryPhysicalRow> {
        val active = if(activeOnly) "h.deletedAt IS NULL AND " else ""
        val sql = "SELECT h.*, r.remoteId AS recoveryRemoteId, r.localChangeRevision AS recoveryLocalRevision, " +
            "r.lastSyncedLocalRevision AS recoverySyncedRevision, r.lastRemotePayloadFingerprint AS recoveryPayloadFingerprint " +
            "FROM history_entries h INNER JOIN history_entry_remote_refs r ON r.historyEntryUid = h.uid " +
            "WHERE $active(? IS NULL OR r.remoteId > ?) " +
            "AND (? = 0 OR (r.lastRemoteAppliedAt IS NOT NULL AND r.localChangeRevision = r.lastSyncedLocalRevision " +
            "AND h.syncStatus = 'SYNCED_SUCCESSFULLY')) ORDER BY r.remoteId LIMIT ?"
        return db.openHelper.readableDatabase.query(sql,arrayOf<Any?>(after,after,if(onlyClean) 1 else 0,limit)).use { cursor ->
            buildList { while(cursor.moveToNext()) add(requireNotNull(historyRows[cursor.getLong(cursor.getColumnIndexOrThrow("uid"))])) }
        }
    }

    @Test fun manifestNullableEmptyNonUuidAndScopedCursorsMatchOriginalSql() {
        runTest {
            val ids = listOf(""," ","\u0001","!","-","0","A","a","not-a-uuid","quote'--","z","\u007f","\u00e9")
            val rows = listOf("gen-A","gen-a").flatMap { generation ->
                listOf("prices",LOCAL_ACK_BODY_PREFIX+"prices","products").flatMap { domain ->
                    ids.mapIndexed { index,id -> SyncRecoveryManifestRow(generation,domain,id,index % 2 == 0,
                        "id-$index","version-$index",if(index % 3 == 0) null else "identity-$index",
                        if(index % 4 == 0) null else "body-$index") }
                }
            }
            insertManifest(rows)
            val dao = db.syncRecoveryManifestDao()
            for(generation in listOf("gen-A","missing")) for(domain in listOf("prices",LOCAL_ACK_BODY_PREFIX+"prices","missing")) {
                for(after in listOf(null,""," ","!","A","a","not-a-uuid","zz")) for(limit in listOf(-1,0,1,4,500)) {
                    assertEquals(originalManifest(generation,domain,after,limit,false),dao.page(generation,domain,after,limit))
                    assertEquals(originalManifest(generation,domain,after,limit,true),dao.pageActive(generation,domain,after,limit))
                }
            }
            assertEquals("The NULL first page retains a malformed empty ID", "",dao.page("gen-A","prices",null,1).single().remoteId)
            assertTrue(dao.page("gen-A","prices","",500).none { it.remoteId.isEmpty() })
        }
    }

    @Test fun manifestFiveHundredBoundariesPreserveCompleteOrderedRowsAndTombstones() {
        runTest {
            val rows = (0..1000).map { index -> SyncRecoveryManifestRow("generation","prices",
                "id-${index.toString().padStart(4,'0')}",index % 3 != 0,"id-$index","version-$index","identity-$index","body-$index") }
            insertManifest(rows+listOf(rows.first().copy(generationId="foreign"),rows.first().copy(domain="products")))
            val dao = db.syncRecoveryManifestDao()
            for(active in listOf(false,true)) {
                val result = mutableListOf<SyncRecoveryManifestRow>(); var cursor: String? = null
                do {
                    val page = if(active) dao.pageActive("generation","prices",cursor,500) else dao.page("generation","prices",cursor,500)
                    assertEquals(originalManifest("generation","prices",cursor,500,active),page)
                    result += page; cursor=page.lastOrNull()?.remoteId
                } while(page.size == 500)
                assertEquals(originalManifest("generation","prices",null,-1,active),result)
                assertEquals(result.size,result.map { it.remoteId }.toSet().size)
                assertTrue(result.all { it.generationId=="generation" && it.domain=="prices" && (!active || it.active) })
            }
            for(after in listOf(null,"","id-0499","id-0500","id-0999","id-1000","zz")) for(limit in listOf(0,1,500,501)) {
                assertEquals(originalManifest("generation","prices",after,limit,false),dao.page("generation","prices",after,limit))
            }
        }
    }

    @Test fun historyNullableEmptyMalformedDirtyAndDeletedRowsMatchOriginalSql() {
        runTest {
            insertHistory(listOf(""," ","\u0001","!","0","A","a","not-a-uuid","quote'--","z","\u007f","\u00e9"),true)
            val dao = db.historyEntryDao()
            val liveUnapplied = historyRows.values.single { it.remoteId == "\u0001" }
            val liveUnappliedRef = requireNotNull(db.historyEntryRemoteRefDao().getByHistoryEntryUid(liveUnapplied.entry.uid))
            assertNull(liveUnapplied.entry.deletedAt)
            assertEquals(SyncStatus.SYNCED_SUCCESSFULLY,liveUnapplied.entry.syncStatus)
            assertEquals(liveUnapplied.localRevision,liveUnapplied.syncedRevision)
            assertNotNull(liveUnapplied.payloadFingerprint)
            assertNull(liveUnappliedRef.lastRemoteAppliedAt)
            for(after in listOf(null,""," ","0","A","a","not-a-uuid","zz")) for(limit in listOf(-1,0,1,4,500)) {
                // Omitted arguments must still behave like false on this adversarial mixed fixture.
                assertEquals(originalHistory(after,limit,false,false),dao.getRecoveryPhysicalPage(after,limit))
                assertEquals(originalHistory(after,limit,false,true),dao.getRecoveryActivePhysicalPage(after,limit))
                for(onlyClean in listOf(false,true)) {
                    assertEquals(originalHistory(after,limit,onlyClean,false),dao.getRecoveryPhysicalPage(after,limit,onlyClean))
                    assertEquals(originalHistory(after,limit,onlyClean,true),dao.getRecoveryActivePhysicalPage(after,limit,onlyClean))
                }
            }
            assertEquals("",dao.getRecoveryPhysicalPage(null,1,false).single().remoteId)
            assertTrue(dao.getRecoveryPhysicalPage("",500,false).none { it.remoteId.isEmpty() })
            assertTrue(dao.getRecoveryActivePhysicalPage(null,500,false).all { it.entry.deletedAt==null })
            assertTrue(dao.getRecoveryPhysicalPage(null,500,true).all { it.localRevision==it.syncedRevision && it.entry.syncStatus==SyncStatus.SYNCED_SUCCESSFULLY })
        }
    }

    @Test fun historyFiveHundredBoundariesRetainAllBodiesAndDefaultCleanFlags() {
        runTest {
            insertHistory(listOf("")+(1..501).map { "id-${it.toString().padStart(4,'0')}" },false)
            val dao = db.historyEntryDao()
            val full = mutableListOf<HistoryRecoveryPhysicalRow>(); var cursor: String? = null
            do {
                val page = dao.getRecoveryActivePhysicalPage(cursor,500)
                assertEquals(originalHistory(cursor,500,false,true),page)
                full += page; cursor=page.lastOrNull()?.remoteId
            } while(page.size == 500)
            assertEquals(502,full.size)
            assertEquals(originalHistory(null,-1,false,true),full)
            assertEquals(full.size,full.map { it.remoteId }.toSet().size)
            for(after in listOf(null,"","id-0499","id-0500","id-0501","zz")) for(limit in listOf(0,1,500,501)) {
                assertEquals(originalHistory(after,limit,false,false),dao.getRecoveryPhysicalPage(after,limit))
            }
        }
    }
}
