package com.example.merchandisecontrolsplitview.data

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Component checks of the production private parser/query, using actual Room rather than a second DAO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PriceParentProjectionTest {
    private lateinit var db: AppDatabase
    private val queries = ConcurrentLinkedQueue<String>()

    @Before
    fun setup() {
        val app: Application = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, _ -> queries.add(sql) }, Executor { it.run() })
            .build()
    }

    @After
    fun teardown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun deletionParserMatchesOriginalLimitSevenSplitAndUnchangedTimestampOracle() {
        val vectors = mutableListOf(
            ID to productLine(ID),
            ID to productLine(ID, TIMESTAMP),
            ID to productLine(ID, "0000-99-99T99:99:99.999999Z"),
            ID to productLine(ID, ""),
            ID to productLine(ID, "2026-10-27 12:00:00"),
            ID to productLine(ID, "2026-10-27T12:00:00.000Z"),
            ID to productLine(ID, "2026-10-27T12:00:00.000000+00:00"),
            ID to productLine(ID, "$TIMESTAMP "),
            ID to productLine(ID, "漢字"),
            ID to productLine(ID, TIMESTAMP, ""),
            ID to productLine(ID, TIMESTAMP, "尾\u001f\u001fextra\u001f"),
            ID to productLine(ID, "-", "\u001f"),
            ID to productLine(OTHER_ID),
            "" to productLine(""),
            "商品🙂" to productLine("商品🙂", TIMESTAMP, "空\u001f末尾"),
            ID.uppercase() to productLine(ID.uppercase(), TIMESTAMP),
            " $ID" to productLine(ID),
            ID to "",
            ID to "\u001f".repeat(6)
        )
        for (fieldCount in 1..6) {
            vectors += ID to List(fieldCount) { index -> if (index == 0) ID else "-" }
                .joinToString("\u001f")
        }
        for ((remoteId, versionLine) in vectors) {
            // The timestamp grammar deliberately comes from the unchanged production function.
            val expected = parseOutcome { originalSplitDeletedAt(remoteId, versionLine) }
            assertEquals("direct parser vector $remoteId / $versionLine", expected,
                parseOutcome { invokeDeletedAt(remoteId, versionLine) })
            assertEquals("row delegate vector $remoteId / $versionLine", expected,
                parseOutcome { invokeDeletedAt(manifest(remoteId, versionLine = versionLine)) })
        }
    }

    @Test
    fun actualThreeColumnProjectionEqualsLegacyDaoWithinExactGenerationAndProductsDomain() = runTest {
        val rows = listOf(
            manifest(ID), manifest(OTHER_ID, active = false, deletedAt = TIMESTAMP),
            manifest(ID, generationId = OTHER_GENERATION, versionLine = "foreign generation"),
            manifest(ID, domain = ShopSyncRowDomain.CATEGORIES.wireValue, versionLine = "foreign domain"),
            manifest(THIRD_ID)
        )
        db.syncRecoveryManifestDao().insertAll(rows)
        val requested = listOf(ID, OTHER_ID, MISSING_ID, ID)
        db.withTransaction {
            val expected = db.syncRecoveryManifestDao().getProductsByRemoteIds(GENERATION, requested)
                .associate { it.remoteId to Projection(it.remoteId, it.active, it.versionLine) }
            queries.clear()
            assertEquals(expected, invokeProjection(GENERATION, requested))
            assertEquals(setOf(ID, OTHER_ID), expected.keys)
            assertEquals(listOf("remoteid,active,versionline"), parentSelectColumns())
        }
    }

    @Test
    fun transactionFastPathAndOutsideLegacyReturnSameActiveParentsIncludingTombstones() = runTest {
        db.syncRecoveryManifestDao().insertAll(listOf(
            manifest(ID), manifest(OTHER_ID, active = false, deletedAt = TIMESTAMP), manifest(THIRD_ID)
        ))
        val requested = listOf(OTHER_ID, THIRD_ID, ID, ID)
        queries.clear()
        val legacy = invokeActiveParents(GENERATION, requested)
        assertEquals(setOf(ID, THIRD_ID), legacy)
        assertEquals(listOf("*"), parentSelectColumns())
        db.withTransaction {
            withPriceParentProjection(db, GENERATION) {
                queries.clear()
                assertEquals(legacy, invokeActiveParents(GENERATION, requested))
                assertEquals(listOf("remoteid,active,versionline"), parentSelectColumns())
            }
        }
    }

    @Test
    fun sameTransactionRawVersionAndActiveChangesCannotReuseAnEarlierParentProof() = runTest {
        val original = manifest(ID)
        db.syncRecoveryManifestDao().insertAll(listOf(original))
        db.withTransaction {
            withPriceParentProjection(db, GENERATION) {
                queries.clear()
                assertEquals(setOf(ID), invokeActiveParents(GENERATION, listOf(ID)))
                val sql = db.openHelper.writableDatabase
                sql.execSQL("UPDATE sync_recovery_manifest SET versionLine = ? " +
                    "WHERE generationId = ? AND domain = 'products' AND remoteId = ?",
                    arrayOf(productLine(ID, TIMESTAMP), GENERATION, ID))
                assertContractFailure("recovery_price_parent_manifest_invalid") {
                    invokeActiveParents(GENERATION, listOf(ID))
                }
                sql.execSQL("UPDATE sync_recovery_manifest SET versionLine = ? " +
                    "WHERE generationId = ? AND domain = 'products' AND remoteId = ?",
                    arrayOf(original.versionLine, GENERATION, ID))
                assertEquals(setOf(ID), invokeActiveParents(GENERATION, listOf(ID)))
                sql.execSQL("UPDATE sync_recovery_manifest SET active = 0 " +
                    "WHERE generationId = ? AND domain = 'products' AND remoteId = ?",
                    arrayOf(GENERATION, ID))
                assertContractFailure("recovery_price_parent_manifest_invalid") {
                    invokeActiveParents(GENERATION, listOf(ID))
                }
                sql.execSQL("DELETE FROM sync_recovery_manifest " +
                    "WHERE generationId = ? AND domain = 'products' AND remoteId = ?",
                    arrayOf(GENERATION, ID))
                assertContractFailure("recovery_price_parent_manifest_missing") {
                    invokeActiveParents(GENERATION, listOf(ID))
                }
                assertEquals(List(5) { "remoteid,active,versionline" }, parentSelectColumns())
            }
        }
        assertTrue(db.syncRecoveryManifestDao().getProductsByRemoteIds(GENERATION, listOf(ID)).isEmpty())
    }

    @Test
    fun fiveHundredParentsSucceedAndFiveHundredOneRejectBeforeAnyLookupInBothPaths() = runTest {
        val ids = (1..501).map { "10000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
        db.syncRecoveryManifestDao().insertAll(ids.take(500).map { manifest(it) })
        for (fast in listOf(false, true)) {
            withLookupMode(fast) {
                queries.clear()
                assertContractFailure("recovery_price_parent_lookup_bound_exceeded") {
                    invokeActiveParents(GENERATION, ids)
                }
                assertTrue("501 must reject before SQL, fast=$fast: $queries", queries.isEmpty())
                assertTrue(invokeActiveParents(GENERATION, emptyList()).isEmpty())
                assertTrue("empty input must not query, fast=$fast", queries.isEmpty())
                assertEquals(ids.take(500).toSet(), invokeActiveParents(GENERATION, ids.take(500)))
                assertEquals(listOf(if (fast) "remoteid,active,versionline" else "*"), parentSelectColumns())
            }
        }
    }

    @Test
    fun projectionOptInCannotEscapeItsTransactionOrMatchingGeneration() = runTest {
        db.syncRecoveryManifestDao().insertAll(listOf(manifest(ID)))
        assertFalse(db.inTransaction())
        withPriceParentProjection(db, GENERATION) {
            queries.clear()
            assertEquals(setOf(ID), invokeActiveParents(GENERATION, listOf(ID)))
            assertEquals(listOf("*"), parentSelectColumns())
        }
        db.withTransaction {
            withPriceParentProjection(db, OTHER_GENERATION) {
                queries.clear()
                assertEquals(setOf(ID), invokeActiveParents(GENERATION, listOf(ID)))
                assertEquals(listOf("*"), parentSelectColumns())
            }
        }
        queries.clear()
        assertEquals(setOf(ID), invokeActiveParents(GENERATION, listOf(ID)))
        assertEquals(listOf("*"), parentSelectColumns())
    }

    private suspend fun <T> withLookupMode(fast: Boolean, block: suspend () -> T): T =
        if (fast) db.withTransaction { withPriceParentProjection(db, GENERATION, block) } else block()

    private suspend fun assertContractFailure(code: String, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected $code, received $error", error is ShopSyncContractException)
        assertEquals(code, (error as ShopSyncContractException).code)
    }

    private fun manifest(
        remoteId: String,
        active: Boolean = true,
        deletedAt: String = "-",
        generationId: String = GENERATION,
        domain: String = ShopSyncRowDomain.PRODUCTS.wireValue,
        versionLine: String = productLine(remoteId, deletedAt)
    ) = SyncRecoveryManifestRow(generationId = generationId, domain = domain, remoteId = remoteId,
        active = active, idLine = remoteId, versionLine = versionLine,
        identityLine = "unused identity", payloadDigest = "a".repeat(64))

    private fun productLine(remoteId: String, deletedAt: String = "-", lastField: String = "opaque") =
        listOf(remoteId, TIMESTAMP, deletedAt, "-", "-", "-", lastField).joinToString("\u001f")

    private data class Projection(val remoteId: String, val active: Boolean, val versionLine: String)

    private fun invokeProjection(generation: String, ids: List<String>): Map<String, Projection> {
        val method = facade.getDeclaredMethod("readPriceParentProjection", AppDatabase::class.java,
            String::class.java, List::class.java).apply { isAccessible = true }
        val result = invokeUnwrapped(method, db, generation, ids) as Map<*, *>
        return result.entries.associate { (key, rawValue) ->
            val value = requireNotNull(rawValue)
            fun field(name: String): Any = requireNotNull(value.javaClass.getDeclaredField(name)
                .apply { isAccessible = true }.get(value))
            key as String to Projection(field("remoteId") as String, field("active") as Boolean,
                field("versionLine") as String)
        }
    }

    private suspend fun invokeActiveParents(generation: String, ids: List<String>): Set<String> =
        suspendCoroutineUninterceptedOrReturn { continuation: Continuation<Set<String>> ->
            val method = facade.getDeclaredMethod("activePriceParentIds", AppDatabase::class.java,
                String::class.java, List::class.java, Continuation::class.java).apply { isAccessible = true }
            invokeUnwrapped(method, db, generation, ids, continuation)
        }

    private fun invokeDeletedAt(remoteId: String, versionLine: String): String? =
        invokeUnwrapped(facade.getDeclaredMethod("productDeletedAt", String::class.java, String::class.java)
            .apply { isAccessible = true }, remoteId, versionLine) as String?

    private fun invokeDeletedAt(row: SyncRecoveryManifestRow): String? =
        invokeUnwrapped(facade.getDeclaredMethod("productDeletedAt", SyncRecoveryManifestRow::class.java)
            .apply { isAccessible = true }, row) as String?

    private fun originalSplitDeletedAt(remoteId: String, versionLine: String): String? {
        val parts = versionLine.split('\u001f', limit = 7)
        if (parts.size != 7 || parts[0] != remoteId) {
            throw ShopSyncContractException("recovery_product_manifest_invalid")
        }
        return parts[2].takeUnless { it == "-" }?.let { timestamp ->
            invokeUnwrapped(facade.getDeclaredMethod("canonicalTimestamp", String::class.java)
                .apply { isAccessible = true }, timestamp) as String
        }
    }

    private data class ParseOutcome(val value: String?, val failureClass: String?, val failureMessage: String?)

    private fun parseOutcome(block: () -> String?): ParseOutcome =
        try { ParseOutcome(block(), null, null) }
        catch (error: Exception) { ParseOutcome(null, error.javaClass.name, error.message) }

    private fun invokeUnwrapped(method: Method, vararg args: Any?): Any? =
        try { method.invoke(null, *args) }
        catch (wrapper: InvocationTargetException) { throw requireNotNull(wrapper.cause) }

    private fun parentSelectColumns(): List<String> = queries.map { sql ->
        sql.replace("`", "").lowercase().replace(Regex("\\s+"), " ").trim()
    }.filter { it.startsWith("select ") && it.contains("from sync_recovery_manifest ") &&
        it.contains("remoteid in (") }
        .map { it.substringAfter("select ").substringBefore(" from ").replace(" ", "") }

    private companion object {
        val facade: Class<*> = Class.forName("com.example.merchandisecontrolsplitview.data.ShopSyncRecoveryCoordinatorKt")
        const val GENERATION = "price-parent-generation"
        const val OTHER_GENERATION = "foreign-price-parent-generation"
        const val ID = "10000000-0000-4000-8000-00000000000a"
        const val OTHER_ID = "10000000-0000-4000-8000-00000000000b"
        const val THIRD_ID = "10000000-0000-4000-8000-00000000000c"
        const val MISSING_ID = "10000000-0000-4000-8000-00000000000d"
        const val TIMESTAMP = "2026-10-27T12:00:00.123456Z"
    }
}
