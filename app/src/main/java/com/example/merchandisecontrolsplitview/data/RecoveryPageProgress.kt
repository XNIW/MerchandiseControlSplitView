package com.example.merchandisecontrolsplitview.data

import androidx.sqlite.db.SupportSQLiteDatabase

/** Staging-only metadata. Never copied into the active Room database. */
internal class RecoveryPageProgress(private val sql: SupportSQLiteDatabase) {
    data class Accepted(
        val afterId: String?, val complete: Boolean, val pages: Long,
        val rows: Long, val bytes: Long, val largestRowBytes: Long
    )

    fun initialize(journal: SyncRecoveryJournal, checkpoint: ShopSyncRecoveryCheckpoint) {
        sql.execSQL("""CREATE TABLE recovery_transfer_header (
            id INTEGER PRIMARY KEY CHECK(id=1), runId TEXT NOT NULL, ownerHash TEXT NOT NULL,
            storeScope TEXT NOT NULL, shopId TEXT NOT NULL, deviceId TEXT NOT NULL,
            checkpointJson TEXT NOT NULL, checkpointDigest TEXT NOT NULL)""")
        sql.execSQL("""CREATE TABLE recovery_transfer_progress (
            domain TEXT PRIMARY KEY, afterId TEXT, complete INTEGER NOT NULL,
            pages INTEGER NOT NULL, rows INTEGER NOT NULL, bytes INTEGER NOT NULL,
            largestRowBytes INTEGER NOT NULL)""")
        sql.execSQL("INSERT INTO recovery_transfer_header VALUES(1,?,?,?,?,?,?,?)", arrayOf(
            requireNotNull(journal.runId), journal.ownerHash, journal.storeScope,
            requireNotNull(journal.shopId), journal.deviceId,
            encodeRecoveryCheckpointJson(checkpoint), checkpoint.checkpointDigest
        ))
    }

    fun checkpoint(journal: SyncRecoveryJournal): ShopSyncRecoveryCheckpoint {
        return sql.query("SELECT * FROM recovery_transfer_header WHERE id=1").use { cursor ->
            if (!cursor.moveToFirst() ||
                cursor.getString(1) != journal.runId || cursor.getString(2) != journal.ownerHash ||
                cursor.getString(3) != journal.storeScope || cursor.getString(4) != journal.shopId ||
                cursor.getString(5) != journal.deviceId || cursor.getString(7) != journal.checkpointADigest
            ) throw ShopSyncContractException("recovery_resume_header_mismatch")
            val raw = cursor.getString(6)
            if (raw.length > 262_144) throw ShopSyncContractException("recovery_resume_header_oversized")
            val checkpoint = decodeRecoveryCheckpointJson(raw)
            if (checkpoint.checkpointDigest != journal.checkpointADigest || cursor.moveToNext()) {
                throw ShopSyncContractException("recovery_resume_checkpoint_mismatch")
            }
            checkpoint
        }
    }

    fun accepted(domain: ShopSyncRowDomain): Accepted? = sql.query(
        "SELECT afterId,complete,pages,rows,bytes,largestRowBytes FROM recovery_transfer_progress WHERE domain=?",
        arrayOf(domain.wireValue)
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val state = Accepted(if (cursor.isNull(0)) null else cursor.getString(0), cursor.getInt(1) == 1,
            cursor.getLong(2), cursor.getLong(3), cursor.getLong(4), cursor.getLong(5))
        if (cursor.getInt(1) !in 0..1 || state.pages <= 0 || state.rows < 0 || state.bytes <= 0 ||
            state.largestRowBytes < 0 || (state.complete && state.afterId != null) ||
            (!state.complete && canonicalShopSyncRecoveryEntityIdOrNull(state.afterId.orEmpty()) != state.afterId)
        ) throw ShopSyncContractException("recovery_resume_progress_invalid")
        state
    }

    /** Caller owns the transaction containing BOTH manifest and materialization. */
    fun accept(domain: ShopSyncRowDomain, page: ShopSyncRecoveryPage) {
        val previous = accepted(domain)
        if (previous?.complete == true) throw ShopSyncContractException("recovery_resume_domain_complete")
        fun add(old: Long, delta: Long): Long {
            if (delta < 0 || old > Long.MAX_VALUE - delta) throw ShopSyncContractException("recovery_resume_budget_overflow")
            return old + delta
        }
        sql.execSQL("INSERT OR REPLACE INTO recovery_transfer_progress VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>(
            domain.wireValue, page.nextAfterId, if (page.hasMore) 0 else 1,
            add(previous?.pages ?: 0, 1), add(previous?.rows ?: 0, page.rows.size.toLong()),
            add(previous?.bytes ?: 0, page.responseBytes),
            maxOf(previous?.largestRowBytes ?: 0, page.largestRowBytes)
        ))
    }
}
