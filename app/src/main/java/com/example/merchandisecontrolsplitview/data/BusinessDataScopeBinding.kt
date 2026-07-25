package com.example.merchandisecontrolsplitview.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Binding single-row del cache business locale. Vive nello stesso Room DB dei
 * dati che protegge, così controllo di vuoto e prima associazione sono una sola
 * transazione verificabile.
 */
@Entity(tableName = "business_data_scope_binding")
data class BusinessDataScopeBinding(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val ownerHash: String,
    val storeId: String,
    val localStoreId: String,
    val syncProtocolVersion: Int,
    val schemaVersion: Int,
    val storeEpoch: Int,
    val boundAtMs: Long
) {
    fun toOwnerStoreScope(): Task126OwnerStoreScope =
        Task126OwnerStoreScope(
            ownerHash = ownerHash,
            storeId = storeId,
            localStoreId = localStoreId,
            syncProtocolVersion = syncProtocolVersion,
            schemaVersion = schemaVersion,
            storeEpoch = storeEpoch
        )

    companion object {
        const val SINGLETON_ID = 1

        fun from(scope: Task126OwnerStoreScope, boundAtMs: Long): BusinessDataScopeBinding =
            BusinessDataScopeBinding(
                ownerHash = scope.ownerHash,
                storeId = scope.storeId,
                localStoreId = scope.localStoreId,
                syncProtocolVersion = scope.syncProtocolVersion,
                schemaVersion = scope.schemaVersion,
                storeEpoch = scope.storeEpoch,
                boundAtMs = boundAtMs
            )
    }
}
@Dao
interface BusinessDataScopeBindingDao {
    @Query("SELECT * FROM business_data_scope_binding WHERE id = 1 LIMIT 1")
    suspend fun get(): BusinessDataScopeBinding?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: BusinessDataScopeBinding)
}

internal fun parseLegacyBusinessDataScope(value: String?): Task126OwnerStoreScope? {
    val normalized = value?.trim().orEmpty()
    val separator = normalized.indexOf(':')
    if (separator <= 0 || separator == normalized.lastIndex) return null
    val ownerUserId = normalized.substring(0, separator).trim()
    val legacyStore = normalized.substring(separator + 1).trim()
    if (ownerUserId.isEmpty() || legacyStore.isEmpty()) return null
    val storeScope = when {
        legacyStore == "legacy" || legacyStore == Task126SyncPolicy.DEFAULT_STORE_ID -> ""
        legacyStore.startsWith("shop:") -> legacyStore
        else -> "shop:$legacyStore"
    }
    return Task126OwnerStoreScope(
        ownerHash = task126OwnerHash(ownerUserId),
        storeId = storeScope,
        localStoreId = null
    )
}
