package com.example.merchandisecontrolsplitview.data

object Task126SyncPolicy {
    const val DEFAULT_STORE_ID = "default"
    const val SYNC_PROTOCOL_VERSION = 126
    const val LOCAL_SCHEMA_VERSION = 2
    const val DEFAULT_STORE_EPOCH = 1
    const val MAX_PRODUCT_PRICE_PAGE_SIZE = 500
    const val ACTIVE_STORE_ONLY = true
    const val OWNER_STORE_MISMATCH_FAIL_CLOSED = true
    const val NO_CROSS_STORE_PENDING_PUSH = true

    val storeScopeMode: StoreScopeMode = StoreScopeMode.LocalDefaultStoreOnly
    val cacheMode: CacheMode = CacheMode.LogicalScope
    val featureFlags = FeatureFlags(
        strictOwnerStoreGate = true,
        conflictReviewV2 = true,
        physicalMultiStoreCache = false
    )
}

enum class StoreScopeMode {
    LocalDefaultStoreOnly,
    RemoteStoreAware
}

enum class CacheMode {
    LogicalScope,
    PhysicalStore
}

data class FeatureFlags(
    val strictOwnerStoreGate: Boolean,
    val conflictReviewV2: Boolean,
    val physicalMultiStoreCache: Boolean
)

class Task126OwnerStoreScope(
    ownerHash: String,
    storeId: String?,
    localStoreId: String?,
    val syncProtocolVersion: Int = Task126SyncPolicy.SYNC_PROTOCOL_VERSION,
    val schemaVersion: Int = Task126SyncPolicy.LOCAL_SCHEMA_VERSION,
    val storeEpoch: Int = Task126SyncPolicy.DEFAULT_STORE_EPOCH
) {
    val ownerHash: String = ownerHash.trim()
    val storeId: String = normalizedStoreId(storeId)
    val localStoreId: String = normalizedLocalStoreId(localStoreId, this.storeId)

    companion object {
        fun normalizedStoreId(value: String?): String =
            value?.trim()?.takeIf { it.isNotEmpty() } ?: Task126SyncPolicy.DEFAULT_STORE_ID

        fun normalizedLocalStoreId(value: String?, storeId: String): String =
            value?.trim()?.takeIf { it.isNotEmpty() } ?: "local-$storeId"
    }
}

data class Task126OutboxEntryScope(
    val ownerUserId: String,
    val storeId: String?,
    val localStoreId: String?,
    val syncProtocolVersion: Int = Task126SyncPolicy.SYNC_PROTOCOL_VERSION
)

sealed class Task126OwnerStoreGateDecision {
    data object Allowed : Task126OwnerStoreGateDecision()
    data class Blocked(val reason: Reason) : Task126OwnerStoreGateDecision()

    enum class Reason {
        OwnerMismatch,
        StoreMismatch,
        LocalStoreMismatch,
        SchemaMismatch
    }
}

object Task126OwnerStoreGate {
    fun validate(
        entry: Task126OutboxEntryScope,
        activeOwnerUserId: String,
        activeStoreId: String,
        activeLocalStoreId: String? = null
    ): Task126OwnerStoreGateDecision {
        if (entry.ownerUserId.trim() != activeOwnerUserId.trim()) {
            return Task126OwnerStoreGateDecision.Blocked(Task126OwnerStoreGateDecision.Reason.OwnerMismatch)
        }

        val expectedStore = Task126OwnerStoreScope.normalizedStoreId(activeStoreId)
        val entryStore = Task126OwnerStoreScope.normalizedStoreId(entry.storeId)
        if (entryStore != expectedStore) {
            return Task126OwnerStoreGateDecision.Blocked(Task126OwnerStoreGateDecision.Reason.StoreMismatch)
        }

        if (activeLocalStoreId != null) {
            val expectedLocal = Task126OwnerStoreScope.normalizedLocalStoreId(activeLocalStoreId, expectedStore)
            val entryLocal = Task126OwnerStoreScope.normalizedLocalStoreId(entry.localStoreId, entryStore)
            if (entryLocal != expectedLocal) {
                return Task126OwnerStoreGateDecision.Blocked(Task126OwnerStoreGateDecision.Reason.LocalStoreMismatch)
            }
        }

        if (entry.syncProtocolVersion != Task126SyncPolicy.SYNC_PROTOCOL_VERSION) {
            return Task126OwnerStoreGateDecision.Blocked(Task126OwnerStoreGateDecision.Reason.SchemaMismatch)
        }

        return Task126OwnerStoreGateDecision.Allowed
    }
}

data class Task126ConflictMatrixCase(val id: String)

object Task126ConflictMatrix {
    val allCases: List<Task126ConflictMatrixCase> =
        (0..60).map { Task126ConflictMatrixCase("C126-%02d".format(it)) }
}

enum class Task126ReviewReason {
    SameField,
    DeleteVsEdit,
    DomainInvariant
}

sealed class Task126ConflictDecision {
    data object AutoMerge : Task126ConflictDecision()
    data class Review(val reason: Task126ReviewReason) : Task126ConflictDecision()
}

object Task126ConflictResolver {
    fun resolve(
        localChangedFields: List<String>,
        remoteChangedFields: List<String>,
        remoteDeleted: Boolean = false,
        domainInvariantViolated: Boolean = false
    ): Task126ConflictDecision {
        if (remoteDeleted || localChangedFields.any(::isDeleteMarker)) {
            return Task126ConflictDecision.Review(Task126ReviewReason.DeleteVsEdit)
        }
        if (domainInvariantViolated) {
            return Task126ConflictDecision.Review(Task126ReviewReason.DomainInvariant)
        }

        val local = localChangedFields.map(::normalizeField).filter(String::isNotEmpty).toSet()
        val remote = remoteChangedFields.map(::normalizeField).filter(String::isNotEmpty).toSet()
        return if (local.intersect(remote).isEmpty()) {
            Task126ConflictDecision.AutoMerge
        } else {
            Task126ConflictDecision.Review(Task126ReviewReason.SameField)
        }
    }

    private fun normalizeField(field: String): String = field.trim().lowercase()

    private fun isDeleteMarker(field: String): Boolean =
        normalizeField(field) in setOf("delete", "deletedat", "tombstone")
}

object Task126ChangedFieldsContract {
    fun isValid(operation: String, changedFields: List<String>): Boolean =
        when (operation.lowercase()) {
            "update", "upsert" -> changedFields.any { it.trim().isNotEmpty() }
            else -> true
        }
}

object Task126ConflictBatchReview {
    data class Item(
        val localChangedFields: List<String>,
        val remoteChangedFields: List<String>,
        val remoteDeleted: Boolean = false,
        val domainInvariantViolated: Boolean = false
    )

    data class Summary(
        val autoMergeCount: Int,
        val reviewCount: Int,
        val reasons: List<Task126ReviewReason>
    )

    fun summarize(items: List<Item>): Summary {
        var autoMergeCount = 0
        val reasons = mutableListOf<Task126ReviewReason>()
        items.forEach { item ->
            when (
                val decision = Task126ConflictResolver.resolve(
                    localChangedFields = item.localChangedFields,
                    remoteChangedFields = item.remoteChangedFields,
                    remoteDeleted = item.remoteDeleted,
                    domainInvariantViolated = item.domainInvariantViolated
                )
            ) {
                Task126ConflictDecision.AutoMerge -> autoMergeCount += 1
                is Task126ConflictDecision.Review -> reasons += decision.reason
            }
        }
        return Summary(
            autoMergeCount = autoMergeCount,
            reviewCount = reasons.size,
            reasons = reasons.distinct().sortedBy { it.ordinal }
        )
    }
}

enum class Task126ProductPriceDecision {
    Append,
    Dedupe,
    ReviewStale
}

object Task126ProductPriceHistoryPolicy {
    fun resolve(existingCanonicalPrice: String?, incomingCanonicalPrice: String): Task126ProductPriceDecision =
        when {
            existingCanonicalPrice == null -> Task126ProductPriceDecision.Append
            existingCanonicalPrice == incomingCanonicalPrice -> Task126ProductPriceDecision.Dedupe
            else -> Task126ProductPriceDecision.ReviewStale
        }

    fun pageLimit(requested: Int): Int =
        requested.coerceIn(1, Task126SyncPolicy.MAX_PRODUCT_PRICE_PAGE_SIZE)
}

data class Task126CacheManifest(
    val ownerHash: String,
    val storeId: String,
    val localStoreId: String,
    val schemaVersion: Int,
    val syncProtocolVersion: Int,
    val storeEpoch: Int,
    val isActive: Boolean,
    val isDirty: Boolean,
    val estimatedBytes: Long
) {
    val privacySafeSnapshot: Task126CacheManifestPrivacySnapshot
        get() = Task126CacheManifestPrivacySnapshot(
            ownerHashRedacted = "redacted:owner",
            storeIdRedacted = "redacted:store",
            localStoreIdRedacted = "redacted:local-store",
            schemaVersion = schemaVersion,
            syncProtocolVersion = syncProtocolVersion,
            storeEpoch = storeEpoch,
            isActive = isActive,
            isDirty = isDirty,
            estimatedBytes = estimatedBytes
        )

    companion object {
        fun fixture(storeId: String, isActive: Boolean, isDirty: Boolean): Task126CacheManifest =
            Task126CacheManifest(
                ownerHash = "owner-fixture",
                storeId = storeId,
                localStoreId = "local-$storeId",
                schemaVersion = Task126SyncPolicy.LOCAL_SCHEMA_VERSION,
                syncProtocolVersion = Task126SyncPolicy.SYNC_PROTOCOL_VERSION,
                storeEpoch = Task126SyncPolicy.DEFAULT_STORE_EPOCH,
                isActive = isActive,
                isDirty = isDirty,
                estimatedBytes = 1_024L
            )
    }
}

data class Task126CacheManifestPrivacySnapshot(
    val ownerHashRedacted: String,
    val storeIdRedacted: String,
    val localStoreIdRedacted: String,
    val schemaVersion: Int,
    val syncProtocolVersion: Int,
    val storeEpoch: Int,
    val isActive: Boolean,
    val isDirty: Boolean,
    val estimatedBytes: Long
)

sealed class Task126CachePolicyDecision {
    data object Allowed : Task126CachePolicyDecision()
    data class Blocked(val reason: Reason) : Task126CachePolicyDecision()

    enum class Reason {
        ActiveStoreMissing,
        InactiveStoreLoaded
    }
}

enum class Task126InactiveCacheCleanupDecision {
    DeleteCleanInactive,
    KeepDirtyRequiresBackupExport,
    KeepActive
}

object Task126CachePolicy {
    fun validateActiveStoreOnly(
        activeStoreId: String,
        loadedManifests: List<Task126CacheManifest>
    ): Task126CachePolicyDecision {
        val normalizedActive = Task126OwnerStoreScope.normalizedStoreId(activeStoreId)
        if (loadedManifests.none { it.storeId == normalizedActive && it.isActive }) {
            return Task126CachePolicyDecision.Blocked(Task126CachePolicyDecision.Reason.ActiveStoreMissing)
        }
        return if (loadedManifests.any { it.storeId != normalizedActive && !it.isActive }) {
            Task126CachePolicyDecision.Blocked(Task126CachePolicyDecision.Reason.InactiveStoreLoaded)
        } else {
            Task126CachePolicyDecision.Allowed
        }
    }

    fun cleanupDecision(manifest: Task126CacheManifest): Task126InactiveCacheCleanupDecision =
        when {
            manifest.isActive -> Task126InactiveCacheCleanupDecision.KeepActive
            manifest.isDirty -> Task126InactiveCacheCleanupDecision.KeepDirtyRequiresBackupExport
            else -> Task126InactiveCacheCleanupDecision.DeleteCleanInactive
        }
}
