package com.example.merchandisecontrolsplitview.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

object SyncRecoveryJournalPhases {
    const val REQUIRED = "required"
    const val STAGING = "staging"
    const val READY_TO_ACTIVATE = "ready_to_activate"
    const val ACTIVATED_CLEANUP_PENDING = "activated_cleanup_pending"
}

object SyncRecoveryAuthorizationModes {
    const val SAME_SCOPE = "same_scope_recovery"
    const val MISMATCH_REPLACE_CONFIRMED = "mismatch_replace_confirmed"
}

internal const val SYNC_RECOVERY_MAX_RECORDED_ATTEMPTS = 1_000_000

internal fun nextSyncRecoveryAttemptCount(current: Int): Int =
    if (current !in 0 until SYNC_RECOVERY_MAX_RECORDED_ATTEMPTS) {
        SYNC_RECOVERY_MAX_RECORDED_ATTEMPTS
    } else {
        current + 1
    }

internal const val SYNC_RECOVERY_REASON_MISMATCH_REPLACE_CONFIRMED =
    "scope_mismatch_replace_confirmed"

/**
 * Journal singleton del recovery business attivo. La riga viene rimossa solo
 * dopo snapshot verificato, attivazione atomica, baseline/watermark pubblicati
 * e cleanup concluso. Un bootstrap ordinario non e' autorizzato a cancellarla.
 */
@Entity(tableName = "sync_recovery_journal")
data class SyncRecoveryJournal(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val ownerHash: String,
    val storeScope: String,
    val shopId: String?,
    val deviceId: String,
    /** Autorizzazione immutabile: non viene sovrascritta dai codici diagnostici di retry. */
    val authorizationMode: String,
    /** Identifica la generation che possiede staging/cleanup e protegge i retry stale. */
    val runId: String? = null,
    val phase: String,
    val reason: String,
    val blockingEventId: Long?,
    val attemptCount: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val nextRetryAtMs: Long?,
    val checkpointADigest: String? = null,
    val checkpointBDigest: String? = null,
    val stagingDatabaseName: String? = null
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Dao
interface SyncRecoveryJournalDao {
    @Query("SELECT * FROM sync_recovery_journal WHERE id = 1 LIMIT 1")
    suspend fun get(): SyncRecoveryJournal?

    @Query(
        """
        SELECT * FROM sync_recovery_journal
        WHERE id = 1 AND ownerHash = :ownerHash AND storeScope = :storeScope
        LIMIT 1
        """
    )
    suspend fun getForScope(ownerHash: String, storeScope: String): SyncRecoveryJournal?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(journal: SyncRecoveryJournal)

    @Query("DELETE FROM sync_recovery_journal")
    suspend fun deleteAll()
}

/**
 * Baseline cloud verificata e pubblicata nello stesso commit della generazione
 * business attiva. Il JSON contiene soltanto conteggi e digest redatti del
 * contratto recovery, mai righe business o URL firmati.
 */
@Entity(tableName = "sync_recovery_baseline")
data class SyncRecoveryBaseline(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val generationId: String,
    val ownerHash: String,
    val storeScope: String,
    val shopId: String,
    val deviceId: String,
    val scopeKind: String,
    val scopeKey: String,
    val checkpointJson: String,
    val activatedAtMs: Long
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Dao
interface SyncRecoveryBaselineDao {
    @Query("SELECT * FROM sync_recovery_baseline WHERE id = 1 LIMIT 1")
    suspend fun get(): SyncRecoveryBaseline?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(baseline: SyncRecoveryBaseline)

    @Query("DELETE FROM sync_recovery_baseline")
    suspend fun deleteAll()
}

/**
 * Manifest canonico della generazione. Conserva esclusivamente ID remoti,
 * timestamp/riferimenti e hash; non duplica payload, immagini o dati utente.
 * Permette di riprovare la convergenza dopo relaunch senza tenere il DB staging.
 */
@Entity(
    tableName = "sync_recovery_manifest",
    primaryKeys = ["generationId", "domain", "remoteId"],
    indices = [Index(value = ["generationId", "domain", "idLine"])]
)
data class SyncRecoveryManifestRow(
    val generationId: String,
    val domain: String,
    val remoteId: String,
    val active: Boolean,
    val idLine: String,
    val versionLine: String,
    val identityLine: String? = null,
    /** Hash dei campi business materializzati, usato per il readback fisico Room. */
    val payloadDigest: String? = null
)

@Dao
interface SyncRecoveryManifestDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(rows: List<SyncRecoveryManifestRow>)

    /**
     * Il tail V6 sostituisce soltanto le righe toccate nello staging isolato.
     * Non viene mai invocato sul manifest della generazione attiva: la sua
     * pubblicazione resta nel singolo commit di activation del coordinator.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<SyncRecoveryManifestRow>)

    @Query(
        """
        DELETE FROM sync_recovery_manifest
        WHERE generationId = :generationId
          AND domain = :domain
          AND remoteId IN (:remoteIds)
        """
    )
    suspend fun deleteByRemoteIds(
        generationId: String,
        domain: String,
        remoteIds: List<String>
    )

    @Query(
        """
        SELECT * FROM sync_recovery_manifest
        WHERE generationId = :generationId AND domain = :domain AND remoteId = :remoteId
        LIMIT 1
        """
    )
    suspend fun get(
        generationId: String,
        domain: String,
        remoteId: String
    ): SyncRecoveryManifestRow?

    @Query(
        """
        SELECT * FROM sync_recovery_manifest
        WHERE generationId = :generationId AND domain = :domain
          AND (:afterId IS NULL OR remoteId > :afterId)
        ORDER BY remoteId
        LIMIT :limit
        """
    )
    suspend fun page(
        generationId: String,
        domain: String,
        afterId: String?,
        limit: Int
    ): List<SyncRecoveryManifestRow>

    @Query(
        """
        SELECT * FROM sync_recovery_manifest
        WHERE generationId = :generationId AND domain = :domain AND active = 1
          AND (:afterId IS NULL OR remoteId > :afterId)
        ORDER BY remoteId
        LIMIT :limit
        """
    )
    suspend fun pageActive(
        generationId: String,
        domain: String,
        afterId: String?,
        limit: Int
    ): List<SyncRecoveryManifestRow>

    @Query(
        """
        SELECT * FROM sync_recovery_manifest
        WHERE generationId = :generationId AND domain = :domain
          AND (:afterIdLine IS NULL OR idLine > :afterIdLine)
        ORDER BY idLine
        LIMIT :limit
        """
    )
    suspend fun pageByIdLine(
        generationId: String,
        domain: String,
        afterIdLine: String?,
        limit: Int
    ): List<SyncRecoveryManifestRow>

    @Query(
        """
        SELECT COUNT(*) FROM sync_recovery_manifest
        WHERE generationId = :generationId AND domain = :domain
        """
    )
    suspend fun count(generationId: String, domain: String): Int

    @Query("DELETE FROM sync_recovery_manifest")
    suspend fun deleteAll()
}
