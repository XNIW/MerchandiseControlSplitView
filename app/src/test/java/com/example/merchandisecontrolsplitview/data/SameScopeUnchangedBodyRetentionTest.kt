package com.example.merchandisecontrolsplitview.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Isolated proposal tests of the real Room schema + overlay, not coordinator/native acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SameScopeUnchangedBodyRetentionTest {
    private lateinit var db: AppDatabase
    private lateinit var sql: SupportSQLiteDatabase
    private val business = linkedSetOf("suppliers", "categories", "products", "product_prices", "history_entries")
    private val copyOrder = listOf("suppliers", "categories", "products", "product_prices",
        "supplier_remote_refs", "category_remote_refs", "product_remote_refs",
        "product_price_remote_refs", "history_entries", "history_entry_remote_refs")
    private val deleteOrder = listOf("product_price_remote_refs", "product_prices", "product_remote_refs",
        "products", "supplier_remote_refs", "category_remote_refs", "suppliers", "categories",
        "history_entry_remote_refs", "history_entries")
    private val refs = listOf("suppliers" to "supplierId", "categories" to "categoryId",
        "products" to "productId", "product_prices" to "productPriceId", "history_entries" to "historyEntryUid")

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        sql = db.openHelper.writableDatabase
        transaction {
            for (i in 1L..2L) {
                insert("suppliers", mapOf("id" to (20+i), "name" to "Supplier$i"))
                insert("categories", mapOf("id" to (30+i), "name" to "Category$i"))
                insert("products", mapOf("id" to (10+i), "barcode" to "BC$i", "productName" to "Product$i",
                    "purchasePrice" to 1.5, "retailPrice" to 2.5, "supplierId" to (20+i), "categoryId" to (30+i)))
                insert("product_prices", mapOf("id" to (40+i), "productId" to (10+i), "type" to "RETAIL",
                    "price" to 2.5, "effectiveAt" to "2026-10-23T00:00:00Z", "source" to "fixture",
                    "note" to "中文🙂", "createdAt" to "2026-10-23T00:00:00Z"))
                insert("history_entries", mapOf("uid" to (50+i), "id" to "history-$i", "displayName" to "History$i",
                    "timestamp" to "2026-10-23T00:00:00Z", "data" to "[]", "editable" to "[]", "complete" to "[]",
                    "supplier" to "Supplier$i", "category" to "Category$i", "wasExported" to 0,
                    "syncStatus" to "SYNCED_SUCCESSFULLY", "orderTotal" to 0.0, "paymentTotal" to 0.0,
                    "missingItems" to 0, "totalItems" to 0, "isManualEntry" to 0))
                for ((table, foreign) in refs) {
                    val (name, base, remote) = when (table) {
                        "suppliers" -> Triple("supplier_remote_refs", 20L, "supplier")
                        "categories" -> Triple("category_remote_refs", 30L, "category")
                        "products" -> Triple("product_remote_refs", 10L, "product")
                        "product_prices" -> Triple("product_price_remote_refs", 40L, "price")
                        else -> Triple("history_entry_remote_refs", 50L, "history")
                    }
                    val values = linkedMapOf<String, Any?>("id" to (100+i), foreign to (base+i), "remoteId" to "$remote-$i")
                    if (table != "product_prices") {
                        values["localChangeRevision"] = 0; values["lastSyncedLocalRevision"] = 0
                        values["lastRemoteAppliedAt"] = 100L; values["lastRemotePayloadFingerprint"] = "unchanged-fingerprint-$i"
                    }
                    insert(name, values)
                }
            }
            // Stage-local IDs deliberately differ. The proof must compare the existing
            // mapped physical projection, not the raw staging rows or fingerprints.
            copyOrder.forEach { sql.execSQL("CREATE TEMP TABLE mc_recovery_source_$it AS SELECT * FROM `$it`") }
            for ((table, foreign) in refs) {
                val key = if (table == "history_entries") "uid" else "id"
                val ref = when(table) {
                    "suppliers" -> "supplier_remote_refs"; "categories" -> "category_remote_refs"
                    "products" -> "product_remote_refs"; "product_prices" -> "product_price_remote_refs"
                    else -> "history_entry_remote_refs"
                }
                sql.execSQL("UPDATE mc_recovery_source_$table SET `$key`=`$key`+1000")
                sql.execSQL("UPDATE mc_recovery_source_$ref SET id=id+1000, `$foreign`=`$foreign`+1000")
                if (table != "product_prices") sql.execSQL("UPDATE mc_recovery_source_$ref SET lastRemoteAppliedAt=200")
            }
            sql.execSQL("UPDATE mc_recovery_source_products SET supplierId=supplierId+1000, categoryId=categoryId+1000")
            sql.execSQL("UPDATE mc_recovery_source_product_prices SET productId=productId+1000")
            sql.execSQL("CREATE TEMP TABLE overlay_write_probe(tableName TEXT)")
            for (table in business) for (event in listOf("INSERT", "UPDATE", "DELETE")) {
                sql.execSQL("CREATE TEMP TRIGGER probe_${table}_$event AFTER $event ON main.`$table` BEGIN " +
                    "INSERT INTO overlay_write_probe VALUES('$table'); END")
            }
        }
    }

    @After fun teardown() { if (::db.isInitialized) db.close() }

    @Test fun exactMappedBodyMatchesFullReplacementAndSkipsOnlyBusinessWrites() {
        val original = snapshot(business.toList())
        val legacy = activate(false, commit = false)
        assertTrue(legacy.businessWrites > 0)
        val retained = activate(true)
        assertEquals(business, retained.retained)
        assertEquals(legacy.rows, retained.rows)
        assertEquals(0L, retained.businessWrites)
        assertEquals(original, snapshot(business.toList()))
        assertEquals("200", text("SELECT lastRemoteAppliedAt FROM product_remote_refs WHERE productId=11"))
        foreignKeysClean()
    }

    @Test fun everyPhysicalColumnDifferenceRejectsFastPathEvenWithSameFingerprintsAndCounts() {
        for (table in business) {
            val key = if (table == "history_entries") "uid" else "id"
            for ((column, type) in columns(table)) transaction(commit = false) {
                val expression = if (type.contains("TEXT")) "COALESCE(`$column`,'') || '差'"
                    else "COALESCE(`$column`,0)+10000.125"
                sql.execSQL("UPDATE mc_recovery_source_$table SET `$column`=$expression " +
                    "WHERE `$key`=(SELECT MIN(`$key`) FROM mc_recovery_source_$table)")
                assertTrue("$table.$column must be compared physically", retainedTables().isEmpty())
            }
        }
    }

    @Test fun categoryCaseOnlyChangeIsNotHiddenByNoCaseCollation() {
        transaction { sql.execSQL("UPDATE mc_recovery_source_categories SET name='category1' WHERE id=1031") }
        assertTrue(transaction { retainedTables() }.isEmpty())
        val legacy = activate(false, commit = false)
        val fallback = activate(true)
        assertTrue(fallback.retained.isEmpty())
        assertEquals(legacy.rows, fallback.rows)
        assertEquals("category1", text("SELECT name FROM categories WHERE id=31"))
    }

    @Test fun uniqueKeySwapsUseFullReplacementWithAllChildrenAndIdsPreserved() {
        transaction {
            sql.execSQL("UPDATE mc_recovery_source_products SET barcode=CASE id WHEN 1011 THEN 'BC2' ELSE 'BC1' END")
            sql.execSQL("UPDATE mc_recovery_source_suppliers SET name=CASE id WHEN 1021 THEN 'Supplier2' ELSE 'Supplier1' END")
            sql.execSQL("UPDATE mc_recovery_source_categories SET name=CASE id WHEN 1031 THEN 'Category2' ELSE 'Category1' END")
        }
        val legacy = activate(false, commit = false)
        val fallback = activate(true)
        assertTrue(fallback.retained.isEmpty())
        assertEquals(legacy.rows, fallback.rows)
        assertEquals("BC2", text("SELECT barcode FROM products WHERE id=11"))
        assertEquals("2", text("SELECT count(*) FROM product_prices"))
        foreignKeysClean()
    }

    @Test fun physicalCorruptionWithUnchangedRemoteReceiptUsesRepairPath() {
        transaction { sql.execSQL("UPDATE products SET productName='corrupt physical row' WHERE id=11") }
        val legacy = activate(false, commit = false)
        val fallback = activate(true)
        assertTrue(fallback.retained.isEmpty())
        assertEquals(legacy.rows, fallback.rows)
        assertEquals("Product1", text("SELECT productName FROM products WHERE id=11"))
    }

    @Test fun remoteDeleteOrExtraActiveIdentityCannotPassBidirectionalProof() {
        transaction {
            sql.execSQL("DELETE FROM mc_recovery_source_product_price_remote_refs WHERE productPriceId=1041")
            sql.execSQL("DELETE FROM mc_recovery_source_product_prices WHERE id=1041")
            sql.execSQL("DELETE FROM mc_recovery_source_product_remote_refs WHERE productId=1011")
            sql.execSQL("DELETE FROM mc_recovery_source_products WHERE id=1011")
        }
        val legacy = activate(false, commit = false)
        val fallback = activate(true)
        assertTrue(fallback.retained.isEmpty())
        assertEquals(legacy.rows, fallback.rows)
        assertEquals("0", text("SELECT count(*) FROM products WHERE id=11"))
        foreignKeysClean()
    }

    @Test fun lateLocalSaveUsesOriginalPendingOverlayAndKeepsItsPhysicalId() {
        // Models a Save committed after staging, before the activation writer starts.
        transaction { insert("products", mapOf("id" to 99L, "barcode" to "late-save", "productName" to "Local draft")) }
        val legacy = activate(false, commit = false)
        val fallback = activate(true)
        assertTrue(fallback.retained.isEmpty())
        assertEquals(legacy.rows, fallback.rows)
        assertEquals("Local draft", text("SELECT productName FROM products WHERE id=99"))
    }

    @Test fun rawEvidenceAndTombstoneEachIndependentlyForceOriginalPathWithoutMutation() {
        transaction(commit = false) {
            // Opaque raw evidence tests the conservative gate, not R4 codec validity.
            insert("sync_event_outbox", mapOf("ownerUserId" to "owner", "storeScope" to "shop:store",
                "domain" to "PRODUCT", "eventType" to BUSINESS_RESOLUTION_EVENT, "clientEventId" to "opaque-evidence",
                "changedCount" to 0, "entityIdsJson" to "[]", "metadataJson" to "{\"opaque\":true}",
                "createdAtMs" to 1, "attemptCount" to 0))
            val before = snapshot(listOf("sync_event_outbox"))
            assertTrue(retainedTables().isEmpty())
            assertEquals(before, snapshot(listOf("sync_event_outbox")))
        }
        transaction(commit = false) {
            insert("pending_catalog_tombstones", mapOf("entityType" to "PRODUCT", "remoteId" to "missing-remote",
                "enqueuedAtMs" to 1, "attemptCount" to 0))
            val before = snapshot(listOf("pending_catalog_tombstones"))
            assertTrue(retainedTables().isEmpty())
            assertEquals(before, snapshot(listOf("pending_catalog_tombstones")))
        }
    }

    @Test fun rollbackAfterReferenceRefreshRestoresWholeOriginalStore() {
        val before = snapshot(copyOrder + listOf("sync_event_outbox", "pending_catalog_tombstones"))
        val proposed = activate(true, commit = false)
        assertEquals(business, proposed.retained)
        assertEquals(before, snapshot(copyOrder + listOf("sync_event_outbox", "pending_catalog_tombstones")))
    }

    private data class Outcome(val retained: Set<String>, val rows: Map<String, List<List<String?>>>, val businessWrites: Long)

    /** Mirrors only the proposed call-site business block; no generation/journal claim. */
    private fun activate(fast: Boolean, commit: Boolean = true): Outcome = transaction(commit) {
        sql.execSQL("DELETE FROM overlay_write_probe")
        val overlay = SameScopeRecoveryOverlay(sql)
        try {
            overlay.capture()
            val retained = if (fast) overlay.unchangedBusinessTablesToRetain() else emptySet()
            deleteOrder.filterNot { it in retained }.forEach { sql.execSQL("DELETE FROM `$it`") }
            copyOrder.filterNot { it in retained }.forEach(overlay::copyRemoteTable)
            overlay.restorePending()
            overlay.verifyPreservedIntent()
            foreignKeysClean()
            Outcome(retained, snapshot(copyOrder + listOf("sync_event_outbox", "pending_catalog_tombstones")),
                requireNotNull(text("SELECT count(*) FROM overlay_write_probe")).toLong())
        } finally { overlay.close() }
    }

    private fun retainedTables(): Set<String> {
        val overlay = SameScopeRecoveryOverlay(sql)
        return try { overlay.capture(); overlay.unchangedBusinessTablesToRetain() } finally { overlay.close() }
    }

    private fun <T> transaction(commit: Boolean = true, block: () -> T): T {
        sql.beginTransaction()
        return try { block().also { if (commit) sql.setTransactionSuccessful() } } finally { sql.endTransaction() }
    }

    private fun insert(table: String, values: Map<String, Any?>) {
        sql.execSQL("INSERT INTO `$table` (${values.keys.joinToString(",") { "`$it`" }}) VALUES(" +
            values.keys.joinToString(",") { "?" } + ")", values.values.toTypedArray())
    }

    private fun text(query: String): String? = sql.query(query).use { if (it.moveToFirst()) it.getString(0) else null }
    private fun columns(table: String): List<Pair<String, String>> = sql.query("PRAGMA table_info(`$table`)").use {
        buildList { while(it.moveToNext()) add(it.getString(1) to it.getString(2)) }
    }
    private fun snapshot(tables: List<String>): Map<String, List<List<String?>>> = tables.associateWith { table ->
        sql.query("SELECT * FROM `$table` ORDER BY 1").use { c -> buildList {
            while(c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) })
        } }
    }
    private fun foreignKeysClean() { sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) } }
}
