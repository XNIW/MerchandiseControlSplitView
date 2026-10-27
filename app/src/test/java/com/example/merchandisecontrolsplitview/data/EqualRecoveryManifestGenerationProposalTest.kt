package com.example.merchandisecontrolsplitview.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.InvocationTargetException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the actual private proposal helper on Room. This is not coordinator acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class EqualRecoveryManifestGenerationProposalTest {
    private lateinit var db: AppDatabase
    private lateinit var sql: SupportSQLiteDatabase
    private val active = "sync_recovery_manifest"
    private val staged = "mc_recovery_source_sync_recovery_manifest"
    private val domains = listOf("suppliers", "categories", "products", "prices", "sessions",
        "local-ack-body-v1:products", "local-ack-body-v1:prices")
    private val oldGeneration = "old-generation"
    private val newGeneration = "new-generation"
    private val invokeHelper by lazy {
        Class.forName("com.example.merchandisecontrolsplitview.data.ShopSyncRecoveryCoordinatorKt")
            .getDeclaredMethod("advanceEqualRecoveryManifestGeneration", SupportSQLiteDatabase::class.java,
                String::class.java, String::class.java).apply { isAccessible = true }
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        sql = db.openHelper.writableDatabase
        transaction {
            sql.execSQL("INSERT INTO sync_recovery_baseline VALUES(1,?,'owner','shop:store','store','device'," +
                "'shop','store','old-checkpoint',100)", arrayOf(oldGeneration))
            domains.forEachIndexed { i, domain ->
                sql.execSQL("INSERT INTO sync_recovery_manifest VALUES(?,?,?,1,?,?,?,?)",
                    arrayOf<Any?>(oldGeneration, domain, "remote-$i", "id-$i", "version-$i",
                        if (i % 2 == 0) null else "identity-$i", "payload-$i"))
            }
            sql.execSQL("CREATE TEMP TABLE $staged AS SELECT * FROM $active")
            sql.execSQL("UPDATE $staged SET generationId=?", arrayOf(newGeneration))
            sql.execSQL("CREATE TEMP TABLE mc_recovery_source_sync_recovery_baseline AS SELECT * FROM sync_recovery_baseline")
            sql.execSQL("UPDATE mc_recovery_source_sync_recovery_baseline SET generationId=?,checkpointJson='new-checkpoint',activatedAtMs=200",
                arrayOf(newGeneration))
            sql.execSQL("CREATE TEMP TABLE manifest_write_probe(operation TEXT)")
            for (event in listOf("INSERT", "UPDATE", "DELETE")) sql.execSQL(
                "CREATE TEMP TRIGGER manifest_probe_$event AFTER $event ON main.$active BEGIN " +
                    "INSERT INTO manifest_write_probe VALUES('$event'); END")
        }
    }

    @After fun teardown() { if (::db.isInitialized) db.close() }

    @Test fun equalBodyIncludingLocalAckMatchesDeleteInsertAndChangesOnlyGeneration() {
        val baselineBefore = rows("sync_recovery_baseline")
        val stageBefore = rows(staged)
        val canonicalBody = rows(active, excludeGeneration = true)
        val legacy = transaction(commit = false) { replaceManifest(); rows(active) }
        transaction { assertTrue(advance()) }
        assertEquals(legacy, rows(active))
        assertEquals(stageBefore, rows(active))
        assertEquals(canonicalBody, rows(active, excludeGeneration = true))
        assertEquals(baselineBefore, rows("sync_recovery_baseline"))
        assertEquals(stageBefore, rows(staged))
        assertEquals(domains.size.toString(), scalar("SELECT count(*) FROM manifest_write_probe WHERE operation='UPDATE'"))
        assertEquals("0", scalar("SELECT count(*) FROM manifest_write_probe WHERE operation<>'UPDATE'"))
    }

    @Test fun everyNonGenerationColumnIncludingAckPayloadDigestIsCompared() {
        val bodyColumns = columns(active).filterNot { it == "generationId" }
        assertEquals(listOf("domain", "remoteId", "active", "idLine", "versionLine", "identityLine", "payloadDigest"), bodyColumns)
        for (domain in listOf("products", "local-ack-body-v1:products")) for (column in bodyColumns) {
            transaction(commit = false) {
                val expression = if (column == "active") "0" else "COALESCE(`$column`,'') || '差'"
                sql.execSQL("UPDATE $staged SET `$column`=$expression WHERE domain=?", arrayOf(domain))
                rejectWithoutMutation("$domain.$column")
            }
        }
    }

    @Test fun nullEmptyAndCaseOnlyValuesAreNotEquivalent() {
        for ((column, replacement) in listOf("identityLine" to "", "payloadDigest" to "PAYLOAD-0",
            "idLine" to "ID-0")) transaction(commit = false) {
            sql.execSQL("UPDATE $staged SET `$column`=? WHERE domain='suppliers'", arrayOf(replacement))
            rejectWithoutMutation("null/case $column")
        }
        transaction(commit = false) {
            sql.execSQL("UPDATE $staged SET payloadDigest=NULL WHERE domain='local-ack-body-v1:prices'")
            rejectWithoutMutation("ACK payload NULL is a different physical value")
        }
    }

    @Test fun mixedOrWrongGenerationsOnEitherSideForceOriginalReplacement() {
        for (table in listOf(active, staged)) transaction(commit = false) {
            sql.execSQL("UPDATE $table SET generationId='other-generation' WHERE domain='products'")
            rejectWithoutMutation("mixed $table")
        }
        transaction(commit = false) {
            sql.execSQL("UPDATE $staged SET generationId=?", arrayOf(oldGeneration))
            rejectWithoutMutation("staging does not carry requested new generation")
        }
        transaction(commit = false) {
            rejectWithoutMutation("captured baseline mismatch", previous = "other-generation")
            rejectWithoutMutation("same generation is not an advance", target = oldGeneration)
            rejectWithoutMutation("missing captured generation", previous = null)
        }
    }

    @Test fun duplicateStageKeyCannotBeHiddenBySetEqualityAndActivePrimaryKeyIsReal() {
        transaction(commit = false) {
            sql.execSQL("INSERT INTO $staged SELECT * FROM $staged WHERE domain='products'")
            rejectWithoutMutation("duplicate staged domain/remoteId")
        }
        transaction(commit = false) {
            val failure = runCatching { sql.execSQL("INSERT INTO $active SELECT * FROM $active WHERE domain='products'") }.exceptionOrNull()
            assertNotNull("Room PK must reject duplicate active key", failure)
        }
    }

    @Test fun missingExtraAndLocalAckOnlyRowsCannotPassEitherDirection() {
        for (table in listOf(active, staged)) transaction(commit = false) {
            sql.execSQL("DELETE FROM $table WHERE domain='local-ack-body-v1:products'")
            rejectWithoutMutation("missing ACK row $table")
        }
        for (table in listOf(active, staged)) transaction(commit = false) {
            val generation = if (table == active) oldGeneration else newGeneration
            sql.execSQL("INSERT INTO $table VALUES(?, 'unknown-domain', 'extra-remote',1,'extra','v',NULL,'extra-payload')",
                arrayOf(generation))
            rejectWithoutMutation("extra domain $table")
        }
    }

    @Test fun emptyManifestMissingOrNonSingletonBaselineAndSchemaMismatchRejectRetention() {
        transaction(commit = false) {
            sql.execSQL("DELETE FROM $active"); sql.execSQL("DELETE FROM $staged")
            rejectWithoutMutation("empty manifests do not prove a single generation")
        }
        for (table in listOf("sync_recovery_baseline", "mc_recovery_source_sync_recovery_baseline")) {
            transaction(commit = false) { sql.execSQL("DELETE FROM $table"); rejectWithoutMutation("missing $table") }
            transaction(commit = false) {
                val names = columns(table)
                val values = names.joinToString(",") { if (it == "id") "2" else "`$it`" }
                sql.execSQL("INSERT INTO $table SELECT $values FROM $table")
                rejectWithoutMutation("extra singleton $table")
            }
        }
        transaction(commit = false) {
            sql.execSQL("ALTER TABLE $staged ADD COLUMN unexpected TEXT")
            rejectWithoutMutation("stage schema cannot silently discard a body column")
        }
    }

    @Test fun failedProofUsesOriginalDeleteInsertWithExactStageReadback() {
        transaction {
            sql.execSQL("UPDATE $staged SET payloadDigest='changed-ack-payload' WHERE domain='local-ack-body-v1:products'")
            assertFalse(advance())
            replaceManifest()
        }
        assertEquals(rows(staged), rows(active))
        assertEquals("0", scalar("SELECT count(*) FROM manifest_write_probe WHERE operation='UPDATE'"))
    }

    @Test fun rollbackAfterGenerationAdvanceAndBaselinePublicationRestoresBoth() {
        val beforeManifest = rows(active)
        val beforeBaseline = rows("sync_recovery_baseline")
        transaction(commit = false) {
            assertTrue(advance())
            sql.execSQL("DELETE FROM sync_recovery_baseline")
            sql.execSQL("INSERT INTO sync_recovery_baseline SELECT * FROM mc_recovery_source_sync_recovery_baseline")
            assertEquals(newGeneration, scalar("SELECT generationId FROM sync_recovery_baseline"))
        }
        assertEquals(beforeManifest, rows(active))
        assertEquals(beforeBaseline, rows("sync_recovery_baseline"))
        assertEquals("0", scalar("SELECT count(*) FROM manifest_write_probe"))
    }

    @Test fun updateFailurePropagatesAndRollsBackInsteadOfReturningFallbackAfterDml() {
        val before = rows(active)
        transaction {
            sql.execSQL("CREATE TEMP TRIGGER reject_manifest_update AFTER UPDATE ON main.$active " +
                "BEGIN SELECT RAISE(ABORT,'fixture-publication-interruption'); END")
        }
        val failure = runCatching { transaction { advance() } }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(before, rows(active))
        assertEquals("0", scalar("SELECT count(*) FROM manifest_write_probe"))
    }

    @Test fun helperRequiresExistingTransaction() {
        val before = rows(active)
        assertTrue(runCatching { advance() }.exceptionOrNull() is IllegalStateException)
        assertEquals(before, rows(active))
    }

    private fun advance(previous: String? = oldGeneration, target: String = newGeneration): Boolean = try {
        invokeHelper.invoke(null, sql, previous, target) as Boolean
    } catch (failure: InvocationTargetException) { throw requireNotNull(failure.cause) }

    private fun rejectWithoutMutation(message: String, previous: String? = oldGeneration, target: String = newGeneration) {
        val before = rows(active)
        val writesBefore = rows("manifest_write_probe")
        assertFalse(message, advance(previous, target))
        assertEquals(message, before, rows(active))
        assertEquals(message, writesBefore, rows("manifest_write_probe"))
    }

    private fun replaceManifest() {
        sql.execSQL("DELETE FROM $active")
        sql.execSQL("INSERT INTO $active SELECT * FROM $staged")
    }
    private fun <T> transaction(commit: Boolean = true, block: () -> T): T {
        sql.beginTransaction()
        return try { block().also { if (commit) sql.setTransactionSuccessful() } } finally { sql.endTransaction() }
    }
    private fun scalar(query: String): String? = sql.query(query).use { if (it.moveToFirst()) it.getString(0) else null }
    private fun columns(table: String): List<String> = sql.query("PRAGMA table_info(`$table`)").use {
        buildList { while(it.moveToNext()) add(it.getString(1)) }
    }
    private data class SqlValue(val storageClass: Int, val text: String?)
    private fun rows(table: String, excludeGeneration: Boolean = false): List<List<SqlValue>> {
        val names = columns(table).filterNot { excludeGeneration && it == "generationId" }
        val projection = names.joinToString(",") { "`$it`" }
        return sql.query("SELECT $projection FROM $table ORDER BY $projection").use { c -> buildList {
            while(c.moveToNext()) add((0 until c.columnCount).map { SqlValue(c.getType(it), if (c.isNull(it)) null else c.getString(it)) })
        } }
    }
}
