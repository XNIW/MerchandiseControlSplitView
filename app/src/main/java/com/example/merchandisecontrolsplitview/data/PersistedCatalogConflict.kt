package com.example.merchandisecontrolsplitview.data

import kotlinx.serialization.Serializable

/** A navigation target is only a hint. Preparing the review requalifies identity and remote state. */
data class PersistedCatalogConflictTarget(
    val productId: Long,
    val ownerUserId: String,
    val shopId: String
)

/** Only the repository can create a decision context. A local dirty row is never its remote snapshot. */
@ConsistentCopyVisibility
data class PersistedCatalogConflictContext internal constructor(
    val decisionId: String,
    val productId: Long,
    val remoteId: String,
    val conflictRevision: Int,
    val local: Product,
    val remote: Product?,
    val conflictingFields: Set<String>,
    val remoteDeleted: Boolean,
    val canResolve: Boolean,
    val blockingReason: String?,
    internal val evidence: PersistedCatalogConflictEvidence
)

sealed interface PersistedCatalogConflictChoice {
    data object AdoptRemote : PersistedCatalogConflictChoice
    data object AdoptRemoteTombstone : PersistedCatalogConflictChoice
    data class SelectedFields(val fields: Set<String>, val desired: Product) : PersistedCatalogConflictChoice
}

sealed interface PersistedCatalogConflictResult {
    data class Resolved(val successorRevision: Int?, val adoptedRemote: Boolean) : PersistedCatalogConflictResult
    data class Changed(val context: PersistedCatalogConflictContext) : PersistedCatalogConflictResult
    data class Blocked(val code: String) : PersistedCatalogConflictResult
}

internal data class PersistedCatalogConflictEvidence(
    val scope: BusinessResolutionScope,
    val generationId: String,
    val checkpointDigest: String,
    val ref: ProductRemoteRef,
    val local: Product,
    val remote: QualifiedCatalogConflictRemote,
    val originals: List<SyncEventOutboxEntry>,
    val provenance: List<SyncEventOutboxEntry>,
    val lease: Task126BusinessDataScopeLease?,
    val residuals: List<PreparedCatalogResolutionResidual> = emptyList()
)

/** Exact local mutation proof, delivered after the Room commit and before secondary UI readback. */
data class ProductEditorSaveReceipt(
    val mutationId: String,
    val product: Product,
    val changed: Boolean
)

internal const val BUSINESS_RESOLUTION_EVENT = "LOCAL_BUSINESS_RESOLUTION_V1"

@Serializable
internal data class BusinessResolutionScope(
    val owner: String,
    val store: String,
    val shop: String,
    val localStore: String,
    val protocol: Int,
    val schema: Int,
    val epoch: Int,
    val device: String
)

@Serializable
internal data class CatalogProductSnapshot(
    val id: Long,
    val barcode: String,
    val itemNumber: String?,
    val productName: String?,
    val secondProductName: String?,
    val purchasePrice: Double?,
    val retailPrice: Double?,
    val oldPurchasePrice: Double?,
    val oldRetailPrice: Double?,
    val supplierId: Long?,
    val categoryId: Long?,
    val stockQuantity: Double?,
    val primaryImageVersionId: String?,
    val primaryImageUpdatedAt: String?
) {
    fun product() = Product(id, barcode, itemNumber, productName, secondProductName,
        purchasePrice, retailPrice, oldPurchasePrice, oldRetailPrice, supplierId, categoryId,
        stockQuantity, primaryImageVersionId, primaryImageUpdatedAt)

    companion object {
        fun from(product: Product) = with(product) {
            CatalogProductSnapshot(id, barcode, itemNumber, productName, secondProductName,
                purchasePrice, retailPrice, oldPurchasePrice, oldRetailPrice, supplierId, categoryId,
                stockQuantity, primaryImageVersionId, primaryImageUpdatedAt)
        }
    }
}

/** The raw server timestamps remain strings; no conversion to millisecond predicates is permitted. */
@Serializable
internal data class QualifiedCatalogConflictRemote(
    val row: InventoryProductRow?,
    val schemaVersion: String,
    val scope: ShopSyncScope,
    val asOfEventMaxId: String,
    val currentScopeEventMaxId: String,
    val minimumDomainEventMaxId: String,
    val materializedDomainEventMaxId: String,
    val domainScope: String,
    val missingIds: List<String>
)

@Serializable
internal data class CatalogIntentProvenance(
    val revision: Int,
    val parentRevision: Int,
    val desired: CatalogProductSnapshot,
    val intendedFields: List<String>,
    val provenBase: InventoryProductRow?,
    val priorResolutionIds: List<String>,
    /** Written before dispatch and retained after ACK; an absent attempt is not proof of no dispatch. */
    val dispatchOperationKeys: List<String> = emptyList(),
    /** Appended after transfer; the prior receipt and its transfer hash remain immutable. */
    val supersedesProvenanceId: String? = null
)

@Serializable
internal data class CatalogResolutionOriginalSeal(
    val entryId: Long,
    val operationKey: String,
    val rawAttempt: String,
    val dispatchCount: Int,
    val lastAttemptAtMs: Long?,
    val noCommitProof: String?
)

@Serializable
internal data class CatalogResolutionResidual(
    val originalOperationKey: String,
    val remoteId: String,
    val revision: Int,
    val newOperationKey: String,
    val rawSuccessorAttempt: String,
    val qualifiedRemote: QualifiedCatalogConflictRemote,
    val provenBaseFingerprint: String
)

@Serializable
internal data class CatalogResolutionDecision(
    val conflictRevision: Int,
    val acknowledgedRevision: Int,
    val originalMask: String?,
    val originalFingerprint: String?,
    val originalDesired: CatalogProductSnapshot,
    val provenBase: InventoryProductRow?,
    val remote: QualifiedCatalogConflictRemote,
    val disposition: String,
    val originalSeals: List<CatalogResolutionOriginalSeal>,
    val provenanceKeys: List<String>,
    val withdrawnRevisions: List<Int>,
    val choice: String,
    val selectedFields: List<String>,
    val projection: CatalogProductSnapshot?,
    val successorRevision: Int?,
    val successorOperationKey: String?,
    val outcome: String = "superseded_locally",
    val residuals: List<CatalogResolutionResidual> = emptyList()
)

/** Captured only inside the transaction that applies a real successful transport acknowledgement.
 * It can retire equivalent rejected operations, never an uncertain operation or a newer revision.
 */
@Serializable
internal data class CatalogActualAcknowledgement(
    val seal: CatalogResolutionOriginalSeal,
    val returnedRow: InventoryProductRow? = null
)

@Serializable
internal data class CatalogResolutionTransfer(
    val previousGeneration: String,
    val previousCheckpointDigest: String,
    val newCheckpointDigest: String,
    val transferredRecordHashes: List<String>,
    /** Immutable lineage anchors, retained after ACK deletion; never dispatch/no-commit authority. */
    val originalAttemptSeals: List<CatalogResolutionOriginalSeal> = emptyList()
)

/** One local lifecycle, in the existing outbox. Only a validated decision can withdraw a revision.
 * Intent provenance and generation transfer are evidence, never cloud work, an ACK or a decision.
 */
@Serializable
internal data class BusinessResolutionRecord(
    val version: Int = 1,
    val kind: String,
    val recordId: String,
    val scope: BusinessResolutionScope,
    val generationId: String,
    val checkpointDigest: String,
    val productId: Long?,
    val remoteId: String?,
    val intent: CatalogIntentProvenance? = null,
    val decision: CatalogResolutionDecision? = null,
    val transfer: CatalogResolutionTransfer? = null,
    val acknowledgement: CatalogActualAcknowledgement? = null
)
