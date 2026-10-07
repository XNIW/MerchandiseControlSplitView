package com.example.merchandisecontrolsplitview.data

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.merchandisecontrolsplitview.productimage.PRODUCT_IMAGE_MAIN_MAX_BYTES
import com.example.merchandisecontrolsplitview.productimage.PRODUCT_IMAGE_MAIN_MAX_SIDE
import com.example.merchandisecontrolsplitview.productimage.PRODUCT_IMAGE_THUMB_MAX_BYTES
import com.example.merchandisecontrolsplitview.productimage.PRODUCT_IMAGE_THUMB_MAX_SIDE
import com.example.merchandisecontrolsplitview.productimage.ProductImageVariant
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext
import kotlin.math.min

internal object ShopSyncRecoveryReasons {
    const val POST_ACTIVATION_CHECKPOINT_CHANGED = "post_activation_checkpoint_changed"
    const val CHECKPOINT_CHANGED_DURING_DOWNLOAD = "checkpoint_changed_during_download"
    const val RECOVERY_FAILED = "recovery_failed"
}

private val SERVER_RESPONSE_BLOCKING_CODES = setOf(
    "checkpoint_resource_exceeded", "checkpoint_invalid_baseline", "checkpoint_integrity_blocked",
    "checkpoint_status_unsupported", "convergence_marker_resource_exceeded",
    "convergence_marker_invalid_baseline", "convergence_marker_integrity_blocked",
    "convergence_marker_status_unsupported", "rpc_response_missing_fields", "rpc_response_invalid"
)

internal sealed interface ShopSyncRecoveryResult {
    data class Activated(
        val checkpoint: ShopSyncRecoveryCheckpoint,
        val generationId: String
    ) : ShopSyncRecoveryResult

    data class RetryRequired(
        val code: String,
        val nextRetryAtMs: Long?
    ) : ShopSyncRecoveryResult

    data class Rejected(val code: String) : ShopSyncRecoveryResult
}

internal object ShopSyncRecoveryTestHooks {
    @Volatile
    var onRecoveryFailure: ((Exception) -> Unit)? = null

    @Volatile
    var afterStagingJournalPersisted: (() -> Unit)? = null

    @Volatile
    var afterReadyJournalPersisted: (() -> Unit)? = null

    @Volatile
    var afterActivationCommitted: (() -> Unit)? = null

    @Volatile
    var afterActiveTableCopied: ((String) -> Unit)? = null

    @Volatile
    var beforeActivationMetadata: (() -> Unit)? = null

    @Volatile
    var beforeStagingValidation: (suspend (AppDatabase) -> Unit)? = null

    @Volatile
    var beforeRetryJournalPersisted: (suspend () -> Unit)? = null

    @Volatile
    var beforeRetryTransaction: (suspend () -> Unit)? = null

    @Volatile
    var afterPageMaterializedBeforeCommit: ((ShopSyncRowDomain) -> Unit)? = null

    fun reset() {
        onRecoveryFailure = null
        afterPageMaterializedBeforeCommit = null
        afterStagingJournalPersisted = null
        afterReadyJournalPersisted = null
        afterActivationCommitted = null
        afterActiveTableCopied = null
        beforeActivationMetadata = null
        beforeStagingValidation = null
        beforeRetryJournalPersisted = null
        beforeRetryTransaction = null
    }
}

/**
 * Full recovery shop-scoped con pubblicazione atomica.
 *
 * Le pagine vengono materializzate in un Room DB temporaneo non osservato dalla
 * UI. Solo dopo checkpoint A/B, digest, conteggi e relazioni coerenti le dieci
 * tabelle business vengono copiate dentro una singola transazione del DB attivo.
 */
internal class ShopSyncRecoveryCoordinator(
    context: Context,
    private val activeDb: AppDatabase,
    private val activeRepository: DefaultInventoryRepository,
    private val remote: ShopSyncReadRemoteDataSource,
    private val registerDeviceForRecovery: suspend (shopId: String) -> Result<ShopDeviceRegistrationResult>,
    private val scopeStillValid: suspend (accountId: String, shopId: String) -> Boolean,
    private val activationBoundary: suspend (block: suspend () -> Unit) -> Unit,
    /**
     * Runs only while the activation boundary still owns the same account/shop
     * lease. Post-activation consumers (notably image-cache invalidation) must
     * receive the resolved scope rather than infer it from mutable app state.
     */
    private val onActivated: suspend (accountId: String, shopId: String) -> Unit = { _, _ -> },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = {},
    private val resourceLimits: ShopSyncRecoveryResourceLimits =
        DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS,
    private val generationSizeBytes: (File) -> Long = ::recoveryGenerationSizeBytes,
    private val activeGenerationSizeBytes: (File) -> Long = generationSizeBytes,
    private val availableStorageBytes: (File) -> Long = { directory -> directory.usableSpace },
    private val checkpointDecoder: (String) -> ShopSyncRecoveryCheckpoint =
        ::decodeRecoveryCheckpointJson,
    private val deleteStagingDatabase: (Context, String) -> Boolean =
        { targetContext, name -> targetContext.deleteDatabase(name) }
) {
    private val appContext = context.applicationContext

    suspend fun recover(
        accountId: String,
        selectedShop: SelectedShop,
        activeScope: Task126OwnerStoreScope
    ): ShopSyncRecoveryResult = withContext(Dispatchers.IO) {
        val shopId = canonicalUuidOrNull(selectedShop.shopId)
            ?: return@withContext ShopSyncRecoveryResult.Rejected("recovery_shop_invalid")
        val expectedActiveScope = task126ActiveOwnerStoreScope(
            ownerUserId = accountId,
            selectedShop = selectedShop.copy(shopId = shopId)
        )
        if (
            Task126OwnerStoreGate.validate(activeScope, expectedActiveScope) !=
            Task126OwnerStoreGateDecision.Allowed
        ) {
            return@withContext ShopSyncRecoveryResult.Rejected(
                "recovery_scope_identity_mismatch"
            )
        }
        val journal = activeDb.syncRecoveryJournalDao().get()
            ?: return@withContext ShopSyncRecoveryResult.Rejected("recovery_journal_missing")
        if (
            journal.ownerHash != activeScope.ownerHash ||
            journal.storeScope != activeScope.storeId ||
            journal.shopId?.lowercase() != shopId
        ) {
            return@withContext ShopSyncRecoveryResult.Rejected("recovery_journal_scope_mismatch")
        }
        val currentDevice = activeDb.syncEventDeviceStateDao().get()?.deviceId
            ?: return@withContext ShopSyncRecoveryResult.Rejected("recovery_device_missing")
        if (currentDevice != journal.deviceId) {
            return@withContext ShopSyncRecoveryResult.Rejected("recovery_device_changed")
        }
        if (!leaseStillValid(accountId, shopId, currentDevice)) {
            return@withContext ShopSyncRecoveryResult.Rejected("recovery_lease_invalid")
        }
        if (!remote.isConfigured) {
            return@withContext retryAfterFailure(
                journal = journal,
                code = "shop_sync_reader_unavailable",
                keepActivatedPhase =
                    journal.phase == SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING,
                expectedRunId = journal.runId,
                retainStagingDatabase = true
            )
        }

        if (journal.phase == SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING) {
            return@withContext resumeActivatedCleanupWithRetry(
                accountId,
                shopId,
                activeScope,
                journal
            )
        }

        var resumedDb: AppDatabase? = null
        var resumedCheckpoint: ShopSyncRecoveryCheckpoint? = null
        val ownedName = journal.stagingDatabaseName?.takeIf {
            journal.runId != null && it == "$STAGING_DATABASE_PREFIX${journal.runId}.db" &&
                validatedStagingFile(it)?.exists() == true && journal.checkpointADigest != null
        }
        if (ownedName != null) {
            val candidate = openStagingDatabase(ownedName)
            try {
                val checkpoint = RecoveryPageProgress(candidate.openHelper.writableDatabase).checkpoint(journal)
                validateRecoveryScopeIdentity(checkpoint.scope, accountId, currentDevice)
                validateRecoveryCheckpointResourceBounds(checkpoint, resourceLimits)
                resumedCheckpoint = checkpoint
                resumedDb = candidate
            } catch (_: Exception) {
                candidate.close()
            }
        }
        if (resumedDb == null && !cleanupKnownStaging(journal.stagingDatabaseName)) {
            return@withContext retryAfterFailure(journal, "recovery_staging_cleanup_deferred", false,
                journal.runId, retainStagingDatabase = true)
        }
        if (!cleanupBoundedOrphanStaging(excluding = ownedName.takeIf { resumedDb != null })) {
            resumedDb?.close()
            return@withContext retryAfterFailure(journal, "recovery_orphan_cleanup_deferred", false,
                journal.runId, retainStagingDatabase = resumedDb != null)
        }

        val replaceConfirmed = journal.authorizationMode ==
            SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED
        if (!replaceConfirmed) {
            val binding = activeDb.businessDataScopeBindingDao().get()?.toOwnerStoreScope()
            val scopedOutbox = activeDb.syncEventOutboxDao().countPendingForScope(accountId, activeScope.storeId)
            if (binding == null || Task126OwnerStoreGate.validate(binding, activeScope) != Task126OwnerStoreGateDecision.Allowed ||
                activeDb.syncEventOutboxDao().countAll() != scopedOutbox) {
                resumedDb?.close()
                return@withContext retryAfterFailure(journal, "recovery_local_pending", false,
                    expectedRunId = journal.runId, retainStagingDatabase = false)
            }
        }
        val generationId = if (resumedDb != null) requireNotNull(journal.runId) else UUID.randomUUID().toString().lowercase()
        val stagingName = "$STAGING_DATABASE_PREFIX$generationId.db"
        val stagingFile = validatedStagingFile(stagingName)
            ?: return@withContext ShopSyncRecoveryResult.Rejected("recovery_staging_path_invalid")
        var stagingDb: AppDatabase? = resumedDb
        var activated = false
        try {
            coroutineContext.ensureActive()
            val runJournal = journal.copy(
                runId = generationId,
                phase = SyncRecoveryJournalPhases.STAGING,
                updatedAtMs = nowMs(),
                nextRetryAtMs = null,
                checkpointADigest = resumedCheckpoint?.checkpointDigest,
                checkpointBDigest = null,
                stagingDatabaseName = stagingName
            )
            updateJournal(expected = journal, next = runJournal)
            ShopSyncRecoveryTestHooks.afterStagingJournalPersisted?.invoke()
            if (replaceConfirmed) {
                // Normal enrollment requires READY, which this confirmed recovery
                // is restoring. Use the same server enrollment with the recovery's
                // existing scope fences; checkpoint authorization remains mandatory.
                if (!leaseStillValid(accountId, shopId, currentDevice)) {
                    throw ShopSyncContractException("recovery_lease_invalid_before_registration")
                }
                if (requireOwnedJournal(generationId) != runJournal) {
                    throw ShopSyncContractException("recovery_journal_changed")
                }
                val registration = registerDeviceForRecovery(shopId)
                if (!leaseStillValid(accountId, shopId, currentDevice)) {
                    throw ShopSyncContractException("recovery_lease_invalid_after_registration")
                }
                if (requireOwnedJournal(generationId) != runJournal) {
                    throw ShopSyncContractException("recovery_journal_changed")
                }
                val response = registration.getOrElse {
                    if (it is CancellationException) throw it
                    throw ShopSyncContractException("recovery_device_registration_failed")
                }
                if (!response.ok || response.shopId?.lowercase() != shopId) {
                    throw ShopSyncContractException("recovery_device_registration_denied")
                }
            }
            val checkpointA = resumedCheckpoint ?: fetchCheckpoint(accountId, shopId, currentDevice, null)
            if (checkpointA.integrity.totalViolationCount != 0L) {
                throw ShopSyncContractException("recovery_remote_integrity_violation")
            }
            journal.blockingEventId?.let { blockingId ->
                if (parseShopSyncMaxEventId(checkpointA.syncEvents.maxId) < blockingId) {
                    throw ShopSyncContractException("recovery_checkpoint_before_blocking_event")
                }
            }
            updateJournal(
                expected = runJournal,
                next = runJournal.copy(
                    updatedAtMs = nowMs(),
                    checkpointADigest = checkpointA.checkpointDigest,
                    checkpointBDigest = null
                )
            )

            if (stagingDb == null) {
                val created = openStagingDatabase(stagingName)
                stagingDb = created
                created.withTransaction {
                    RecoveryPageProgress(created.openHelper.writableDatabase).initialize(
                        requireOwnedJournal(generationId), checkpointA)
                }
            }
            val stagingRepository = DefaultInventoryRepository(stagingDb)
            enforceGenerationStorageBudget(stagingFile, activationReady = false)
            val transferBudget = RecoveryTransferBudget(resourceLimits)
            val progress = RecoveryPageProgress(stagingDb.openHelper.writableDatabase)
            for (domain in ShopSyncRowDomain.entries) {
                progress.accepted(domain)?.let { transferBudget.restore(domain, it) }
            }
            for (domain in ShopSyncRowDomain.entries) {
                downloadDomain(
                    accountId = accountId,
                    shopId = shopId,
                    deviceId = currentDevice,
                    checkpoint = checkpointA,
                    generationId = generationId,
                    domain = domain,
                    stagingDb = requireNotNull(stagingDb),
                    stagingRepository = stagingRepository,
                    stagingFile = stagingFile,
                    transferBudget = transferBudget
                )
            }
            enforceGenerationStorageBudget(stagingFile, activationReady = false)
            coroutineContext.ensureActive()
            if (!leaseStillValid(accountId, shopId, currentDevice)) {
                throw ShopSyncContractException("recovery_lease_invalid_after_download")
            }
            val checkpointB = fetchCheckpoint(
                accountId = accountId,
                shopId = shopId,
                deviceId = currentDevice,
                expectedScope = checkpointA.scope,
                verifiedBaselineId = checkpointA.syncEvents.maxId,
                expectedBaselineScopeKey = checkpointA.scope.key
            )
            if (checkpointB.integrity.totalViolationCount != 0L) {
                throw ShopSyncContractException("recovery_remote_integrity_violation")
            }
            val checkpointForActivation = catchUpStagingThroughCheckpointB(
                accountId = accountId,
                shopId = shopId,
                deviceId = currentDevice,
                checkpointA = checkpointA,
                checkpointB = checkpointB,
                generationId = generationId,
                stagingDb = requireNotNull(stagingDb),
                stagingRepository = stagingRepository,
                stagingFile = stagingFile,
                transferBudget = transferBudget
            )
            // The first live page is fenced at A but can legally observe a
            // later server row. Only the post-tail receipt against B is a
            // publishable proof; validating against A here would incorrectly
            // turn a recoverable A→B delta into a retry loop. This includes
            // relation/readback checks: an image/product pair may straddle
            // live pages until the frozen tail has reconciled it.
            ShopSyncRecoveryTestHooks.beforeStagingValidation?.invoke(requireNotNull(stagingDb))
            validateRelationalManifest(stagingDb, generationId)
            validateManifest(stagingDb, generationId, checkpointForActivation)
            validateStagingDatabase(stagingDb, generationId, checkpointForActivation)
            validatePhysicalSnapshot(stagingDb, generationId, resourceLimits.historyPageRows)
            val markerBeforeActivation = fetchConvergenceMarker(
                accountId = accountId,
                shopId = shopId,
                deviceId = currentDevice,
                scope = checkpointForActivation.scope,
                verifiedBaselineId = checkpointForActivation.syncEvents.maxId
            )
            validateMarkerMatchesCheckpoint(markerBeforeActivation, checkpointForActivation)
            // B was deliberately queried with A as its server-side baseline
            // so that its frozen event tail can be validated. The marker call
            // uses B as `verifiedBaselineId`, therefore its embedded server
            // checkpoint is the canonical C receipt: self-verifying at B and
            // already proven materially equal to the staged B snapshot. Never
            // fabricate C's digest locally or persist raw B instead.
            val publishedCheckpoint = publishedBaselineFromMarker(
                checkpointB = checkpointForActivation,
                markerC = markerBeforeActivation
            )
            val checkpointJson = RECOVERY_JSON.encodeToString(publishedCheckpoint)
            stagingDb.syncRecoveryBaselineDao().upsert(
                SyncRecoveryBaseline(
                    generationId = generationId,
                    ownerHash = activeScope.ownerHash,
                    storeScope = activeScope.storeId,
                    shopId = shopId,
                    deviceId = currentDevice,
                    scopeKind = checkpointForActivation.scope.kind,
                    scopeKey = checkpointForActivation.scope.key,
                    checkpointJson = checkpointJson,
                    activatedAtMs = nowMs()
                )
            )
            val readyJournal = requireOwnedJournal(generationId)
            updateJournal(
                expected = readyJournal,
                next = readyJournal.copy(
                    phase = SyncRecoveryJournalPhases.READY_TO_ACTIVATE,
                    updatedAtMs = nowMs(),
                    checkpointBDigest = publishedCheckpoint.checkpointDigest,
                    stagingDatabaseName = stagingName
                )
            )
            ShopSyncRecoveryTestHooks.afterReadyJournalPersisted?.invoke()
            activationBoundary {
                if (!leaseStillValid(accountId, shopId, currentDevice)) {
                    throw ShopSyncContractException("recovery_lease_invalid_before_activation")
                }
                // Ricalcolato dentro lo stesso boundary che serializza il commit:
                // un writer precedente non può rendere stale size/free-space.
                enforceGenerationStorageBudget(stagingFile, activationReady = true, sameScopeOverlay = !replaceConfirmed)
                activateAtomically(
                    accountId = accountId,
                    shopId = shopId,
                    activeScope = activeScope,
                    deviceId = currentDevice,
                    checkpoint = publishedCheckpoint,
                    runId = generationId,
                    stagingName = stagingName,
                    stagingDb = requireNotNull(stagingDb),
                    replaceConfirmed = replaceConfirmed
                )
                ShopSyncRecoveryTestHooks.afterActivationCommitted?.invoke()
                activated = true
                if (!leaseStillValid(accountId, shopId, currentDevice)) {
                    throw ShopSyncContractException(
                        "recovery_lease_invalid_before_activation_callback"
                    )
                }
                onActivated(accountId, shopId)
                if (!leaseStillValid(accountId, shopId, currentDevice)) {
                    throw ShopSyncContractException(
                        "recovery_lease_invalid_after_activation_callback"
                    )
                }
            }

            val markerAfterActivation = fetchConvergenceMarker(
                accountId = accountId,
                shopId = shopId,
                deviceId = currentDevice,
                scope = publishedCheckpoint.scope,
                verifiedBaselineId = publishedCheckpoint.syncEvents.maxId
            )
            if (!markerMatchesCheckpoint(markerAfterActivation, publishedCheckpoint)) {
                val cleanupJournal = requireOwnedJournal(generationId)
                stagingDb.close()
                stagingDb = null
                val cleanupComplete = cleanupKnownStaging(stagingName)
                return@withContext retryAfterFailure(
                    journal = cleanupJournal,
                    code = ShopSyncRecoveryReasons.POST_ACTIVATION_CHECKPOINT_CHANGED,
                    keepActivatedPhase = false,
                    expectedRunId = generationId,
                    retainStagingDatabase = !cleanupComplete,
                    checkpointADigest = markerAfterActivation.checkpointDigest
                )
            }
            validateManifest(activeDb, generationId, publishedCheckpoint)
            validatePhysicalSnapshot(activeDb, generationId, resourceLimits.historyPageRows, localOverlay = !replaceConfirmed)
            if (!leaseStillValid(accountId, shopId, currentDevice)) {
                throw ShopSyncContractException("recovery_lease_invalid_before_cleanup")
            }
            stagingDb.close()
            stagingDb = null
            if (!cleanupKnownStaging(stagingName)) {
                return@withContext retryAfterFailure(
                    journal = activeDb.syncRecoveryJournalDao().get()
                        ?: throw ShopSyncContractException("recovery_cleanup_journal_lost"),
                    code = "recovery_staging_cleanup_deferred",
                    keepActivatedPhase = true,
                    expectedRunId = generationId,
                    retainStagingDatabase = true
                )
            }
            if (!leaseStillValid(accountId, shopId, currentDevice)) {
                throw ShopSyncContractException("recovery_lease_invalid_after_cleanup")
            }
            activationBoundary {
                clearCleanupJournal(
                    accountId = accountId,
                    shopId = shopId,
                    activeScope = activeScope,
                    deviceId = currentDevice,
                    generationId = generationId
                )
            }
            logger("shop_sync_recovery activated generation=$generationId")
            ShopSyncRecoveryResult.Activated(publishedCheckpoint, generationId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                stagingDb?.close()
                stagingDb = null
                val latest = activeDb.syncRecoveryJournalDao().get() ?: journal
                val durablyActivated = activated || activationWasCommitted(
                    journal = latest,
                    runId = generationId,
                    activeScope = activeScope,
                    deviceId = currentDevice
                )
                val resumable = hasAcceptedRecoveryHeader(stagingName, latest)
                val cleanupComplete = durablyActivated || resumable || cleanupKnownStaging(stagingName)
                retryAfterFailure(
                    journal = latest,
                    code = "recovery_cancelled",
                    keepActivatedPhase = durablyActivated,
                    expectedRunId = generationId,
                    retainStagingDatabase = !cleanupComplete || durablyActivated || resumable
                )
            }
            throw cancelled
        } catch (error: Exception) {
            ShopSyncRecoveryTestHooks.onRecoveryFailure?.invoke(error)
            val contract = error as? ShopSyncContractException
            val code = contract?.code
                ?: error.message?.takeIf { it.startsWith("recovery_") }
                ?: ShopSyncRecoveryReasons.RECOVERY_FAILED
            val missingFields = contract?.missingFields.orEmpty()
                .filter { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }
                .take(16).joinToString(",").ifEmpty { "none" }
            logger(
                "shop_sync_recovery failed type=${error::class.java.simpleName} " +
                    "code=${contract?.code ?: ShopSyncRecoveryReasons.RECOVERY_FAILED} " +
                    "rpc=${contract?.rpcName ?: "none"} missingFields=$missingFields"
            )
            stagingDb?.close()
            stagingDb = null
            val latest = activeDb.syncRecoveryJournalDao().get() ?: journal
            val durablyActivated = activated || activationWasCommitted(
                journal = latest,
                runId = generationId,
                activeScope = activeScope,
                deviceId = currentDevice
            )
            // Invalid/expired protocol fences require a new A. Transport failures
            // keep the accepted prefix; every retry still obtains a fresh B/C.
            val resumable = !durablyActivated && contract == null && hasAcceptedRecoveryHeader(stagingName, latest)
            val cleanupComplete = durablyActivated || resumable || cleanupKnownStaging(stagingName)
            val retry = retryAfterFailure(
                journal = latest,
                code = code,
                keepActivatedPhase = durablyActivated,
                expectedRunId = generationId,
                retainStagingDatabase = !cleanupComplete || durablyActivated || resumable
            )
            if (code in SERVER_RESPONSE_BLOCKING_CODES) {
                // Preserve the durable reason, but do not spend the current
                // automatic retry window on a deterministic server refusal.
                ShopSyncRecoveryResult.Rejected(code)
            } else {
                retry
            }
        } finally {
            stagingDb?.close()
        }
    }

    private suspend fun resumeActivatedCleanupWithRetry(
        accountId: String,
        shopId: String,
        activeScope: Task126OwnerStoreScope,
        journal: SyncRecoveryJournal
    ): ShopSyncRecoveryResult = try {
        finishActivatedCleanup(accountId, shopId, activeScope, journal)
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            retryAfterFailure(
                journal = journal,
                code = "recovery_cancelled",
                keepActivatedPhase = true,
                expectedRunId = journal.runId,
                retainStagingDatabase = true
            )
        }
        throw cancelled
    } catch (error: Exception) {
        logger("shop_sync_recovery cleanup_failed type=${error::class.java.simpleName}")
        retryAfterFailure(
            journal = journal,
            code = (error as? ShopSyncContractException)?.code
                ?: error.message?.takeIf { it.startsWith("recovery_") }
                ?: ShopSyncRecoveryReasons.RECOVERY_FAILED,
            keepActivatedPhase = true,
            expectedRunId = journal.runId,
            retainStagingDatabase = true
        )
    }

    private suspend fun finishActivatedCleanup(
        accountId: String,
        shopId: String,
        activeScope: Task126OwnerStoreScope,
        journal: SyncRecoveryJournal
    ): ShopSyncRecoveryResult {
        val baseline = activeDb.syncRecoveryBaselineDao().get()
            ?: throw ShopSyncContractException("recovery_baseline_missing")
        if (
            baseline.ownerHash != activeScope.ownerHash ||
            baseline.storeScope != activeScope.storeId ||
            baseline.shopId != shopId ||
            baseline.deviceId != journal.deviceId ||
            journal.runId != baseline.generationId
        ) {
            throw ShopSyncContractException("recovery_baseline_scope_mismatch")
        }
        val persisted = try {
            checkpointDecoder(baseline.checkpointJson)
        } catch (_: Exception) {
            throw ShopSyncContractException("recovery_baseline_invalid")
        }
        validateRecoveryScopeIdentity(persisted.scope, accountId, journal.deviceId)
        validateManifest(activeDb, baseline.generationId, persisted)
        validatePhysicalSnapshot(activeDb, baseline.generationId, resourceLimits.historyPageRows,
            localOverlay = journal.authorizationMode == SyncRecoveryAuthorizationModes.SAME_SCOPE)
        val marker = fetchConvergenceMarker(
            accountId = accountId,
            shopId = shopId,
            deviceId = journal.deviceId,
            scope = persisted.scope,
            verifiedBaselineId = persisted.syncEvents.maxId
        )
        if (!markerMatchesCheckpoint(marker, persisted)) {
            val cleanupComplete = cleanupKnownStaging(journal.stagingDatabaseName)
            return retryAfterFailure(
                journal = journal,
                code = ShopSyncRecoveryReasons.POST_ACTIVATION_CHECKPOINT_CHANGED,
                keepActivatedPhase = false,
                expectedRunId = journal.runId,
                retainStagingDatabase = !cleanupComplete,
                checkpointADigest = marker.checkpointDigest
            )
        }
        activationBoundary {
            if (!leaseStillValid(accountId, shopId, journal.deviceId)) {
                throw ShopSyncContractException(
                    "recovery_lease_invalid_before_activation_callback"
                )
            }
            onActivated(accountId, shopId)
            if (!leaseStillValid(accountId, shopId, journal.deviceId)) {
                throw ShopSyncContractException(
                    "recovery_lease_invalid_after_activation_callback"
                )
            }
        }
        if (!cleanupKnownStaging(journal.stagingDatabaseName)) {
            return retryAfterFailure(
                journal = journal,
                code = "recovery_staging_cleanup_deferred",
                keepActivatedPhase = true,
                expectedRunId = journal.runId,
                retainStagingDatabase = true
            )
        }
        if (!leaseStillValid(accountId, shopId, journal.deviceId)) {
            throw ShopSyncContractException("recovery_lease_invalid_after_cleanup")
        }
        activationBoundary {
            clearCleanupJournal(
                accountId = accountId,
                shopId = shopId,
                activeScope = activeScope,
                deviceId = journal.deviceId,
                generationId = baseline.generationId
            )
        }
        return ShopSyncRecoveryResult.Activated(persisted, baseline.generationId)
    }

    private suspend fun fetchCheckpoint(
        accountId: String,
        shopId: String,
        deviceId: String,
        expectedScope: ShopSyncScope?,
        verifiedBaselineId: String = "0",
        expectedBaselineScopeKey: String? = null
    ): ShopSyncRecoveryCheckpoint {
        if (!leaseStillValid(accountId, shopId, deviceId)) {
            throw ShopSyncContractException("recovery_lease_invalid_before_rpc")
        }
        val checkpoint = remote.checkpoint(
            ShopSyncRpcContext(
                accountId = accountId,
                shopId = shopId,
                deviceIdentifier = deviceId,
                expectedScope = expectedScope,
                verifiedBaselineId = verifiedBaselineId,
                expectedBaselineScopeKey = expectedBaselineScopeKey
            )
        ).getOrThrow()
        validateRecoveryScopeIdentity(checkpoint.scope, accountId, deviceId)
        validateRecoveryCheckpointResourceBounds(checkpoint, resourceLimits)
        if (!leaseStillValid(accountId, shopId, deviceId)) {
            throw ShopSyncContractException("recovery_lease_invalid_after_rpc")
        }
        return checkpoint
    }

    private suspend fun fetchConvergenceMarker(
        accountId: String,
        shopId: String,
        deviceId: String,
        scope: ShopSyncScope,
        verifiedBaselineId: String
    ): ShopSyncConvergenceMarker {
        if (!leaseStillValid(accountId, shopId, deviceId)) {
            throw ShopSyncContractException("recovery_lease_invalid_before_marker")
        }
        val marker = remote.convergenceMarker(
            ShopSyncRpcContext(
                accountId = accountId,
                shopId = shopId,
                deviceIdentifier = deviceId,
                expectedScope = scope,
                verifiedBaselineId = verifiedBaselineId,
                expectedBaselineScopeKey = scope.key
            )
        ).getOrThrow()
        validateRecoveryScopeIdentity(marker.scope, accountId, deviceId)
        if (!leaseStillValid(accountId, shopId, deviceId)) {
            throw ShopSyncContractException("recovery_lease_invalid_after_marker")
        }
        return marker
    }

    /**
     * Materializes the durable local representation of the server marker's
     * canonical C receipt. The marker omits individual integrity counters,
     * but validated B has all of them at zero and marker C independently
     * proves a zero total, so the two representations are equivalent.
     */
    /**
     * The recovery pages are live keyset reads. A row changed after checkpoint
     * A may therefore already be visible in staging, while another row of the
     * same generation still reflects A. Never publish that mixed receipt.
     *
     * Checkpoint B freezes the event tail. Every safe event is applied only to
     * the isolated staging database, then the whole staged receipt is checked
     * against B before the existing atomic activation boundary is entered.
     */
    private suspend fun catchUpStagingThroughCheckpointB(
        accountId: String,
        shopId: String,
        deviceId: String,
        checkpointA: ShopSyncRecoveryCheckpoint,
        checkpointB: ShopSyncRecoveryCheckpoint,
        generationId: String,
        stagingDb: AppDatabase,
        stagingRepository: DefaultInventoryRepository,
        stagingFile: File,
        transferBudget: RecoveryTransferBudget
    ): ShopSyncRecoveryCheckpoint {
        validateCheckpointTailBoundary(checkpointA, checkpointB, shopId)
        if (checkpointB.syncEvents.requiresFullRecovery) {
            // A publishable receipt may never carry a fresh incomplete or
            // legacy event, even when its material digest happens to equal A.
            // Leave the durable journal in REQUIRED so a later full snapshot
            // can prove a baseline past that event.
            throw ShopSyncContractException("recovery_tail_requires_full_recovery")
        }
        if (sameRecoveryMaterial(checkpointA, checkpointB)) return checkpointB

        val maxA = parseShopSyncMaxEventId(checkpointA.syncEvents.maxId)
        val maxB = parseShopSyncMaxEventId(checkpointB.syncEvents.maxId)
        if (maxB == maxA) {
            // A material change without a new scoped event has no safe tail.
            // Retrying leaves the active generation intact and surfaces the
            // contract violation instead of accepting a silent divergence.
            throw ShopSyncContractException("recovery_checkpoint_changed_without_event_tail")
        }
        drainTailIntoStaging(
            accountId = accountId,
            shopId = shopId,
            deviceId = deviceId,
            checkpointA = checkpointA,
            checkpointB = checkpointB,
            generationId = generationId,
            stagingDb = stagingDb,
            stagingRepository = stagingRepository,
            stagingFile = stagingFile,
            transferBudget = transferBudget
        )
        enforceGenerationStorageBudget(stagingFile, activationReady = false)
        return checkpointB
    }

    private fun validateCheckpointTailBoundary(
        checkpointA: ShopSyncRecoveryCheckpoint,
        checkpointB: ShopSyncRecoveryCheckpoint,
        shopId: String
    ) {
        if (
            checkpointA.status != "ready" ||
            checkpointB.status != "ready" ||
            checkpointA.shopId.lowercase() != shopId.lowercase() ||
            checkpointB.shopId.lowercase() != shopId.lowercase() ||
            checkpointB.scope != checkpointA.scope ||
            checkpointB.syncEvents.verifiedBaselineId != checkpointA.syncEvents.maxId
        ) {
            throw ShopSyncContractException("recovery_checkpoint_tail_scope_mismatch")
        }
        val maxA = parseShopSyncMaxEventId(checkpointA.syncEvents.maxId)
        val maxB = parseShopSyncMaxEventId(checkpointB.syncEvents.maxId)
        if (maxB < maxA) {
            throw ShopSyncContractException("recovery_checkpoint_tail_regressed")
        }
        setOf(
            SyncEventDomains.CATALOG,
            SyncEventDomains.PRICES,
            SyncEventDomains.HISTORY
        ).forEach { domain ->
            val domainA = parseShopSyncMaxEventId(
                checkpointA.syncEvents.domainMaxIds[domain]
                    ?: throw ShopSyncContractException("recovery_checkpoint_domain_fence_missing")
            )
            val domainB = parseShopSyncMaxEventId(
                checkpointB.syncEvents.domainMaxIds[domain]
                    ?: throw ShopSyncContractException("recovery_checkpoint_domain_fence_missing")
            )
            if (domainB < domainA || domainB > maxB) {
                throw ShopSyncContractException("recovery_checkpoint_tail_domain_regressed")
            }
        }
    }

    private suspend fun drainTailIntoStaging(
        accountId: String,
        shopId: String,
        deviceId: String,
        checkpointA: ShopSyncRecoveryCheckpoint,
        checkpointB: ShopSyncRecoveryCheckpoint,
        generationId: String,
        stagingDb: AppDatabase,
        stagingRepository: DefaultInventoryRepository,
        stagingFile: File,
        transferBudget: RecoveryTransferBudget
    ) {
        val frozenMax = parseShopSyncMaxEventId(checkpointB.syncEvents.maxId)
        var cursor = parseShopSyncMaxEventId(checkpointA.syncEvents.maxId)
        var pages = 0
        while (cursor < frozenMax) {
            if (pages >= RECOVERY_TAIL_MAX_PAGES) {
                throw ShopSyncContractException("recovery_tail_page_bound_exceeded")
            }
            if (!leaseStillValid(accountId, shopId, deviceId)) {
                throw ShopSyncContractException("recovery_lease_invalid_before_tail_page")
            }
            val page = remote.eventPage(
                context = ShopSyncRpcContext(
                    accountId = accountId,
                    shopId = shopId,
                    deviceIdentifier = deviceId,
                    expectedScope = checkpointB.scope,
                    expectedEventMaxId = checkpointB.syncEvents.maxId
                ),
                afterId = cursor,
                limit = RECOVERY_TAIL_EVENT_PAGE_LIMIT
            ).getOrThrow()
            if (!leaseStillValid(accountId, shopId, deviceId)) {
                throw ShopSyncContractException("recovery_lease_invalid_after_tail_page")
            }
            validateTailPage(page, checkpointB, shopId, cursor)
            transferBudget.recordTailEventPage(page.responseBytes)
            if (page.rows.isEmpty()) {
                throw ShopSyncContractException("recovery_tail_event_gap")
            }
            page.rows.forEach { event ->
                coroutineContext.ensureActive()
                if (event.id <= cursor || event.id > frozenMax) {
                    throw ShopSyncContractException("recovery_tail_event_order_invalid")
                }
                validateTailEvent(event, checkpointB, shopId)
                applyTailEventToStaging(
                    accountId = accountId,
                    shopId = shopId,
                    deviceId = deviceId,
                    checkpoint = checkpointB,
                    event = event,
                    generationId = generationId,
                    stagingDb = stagingDb,
                    stagingRepository = stagingRepository,
                    transferBudget = transferBudget
                )
                enforceGenerationStorageBudget(stagingFile, activationReady = false)
                cursor = event.id
            }
            if (page.hasMore) {
                if (page.nextAfterId != cursor) {
                    throw ShopSyncContractException("recovery_tail_cursor_invalid")
                }
            } else if (cursor != frozenMax) {
                throw ShopSyncContractException("recovery_tail_event_gap")
            }
            pages++
        }
    }

    private suspend fun applyTailEventToStaging(
        accountId: String,
        shopId: String,
        deviceId: String,
        checkpoint: ShopSyncRecoveryCheckpoint,
        event: SyncEventRemoteRow,
        generationId: String,
        stagingDb: AppDatabase,
        stagingRepository: DefaultInventoryRepository,
        transferBudget: RecoveryTransferBudget
    ) {
        val ids = event.entityIds ?: SyncEventEntityIds()
        if (ids.isEmpty) return
        when (event.domain) {
            SyncEventDomains.CATALOG -> stageCatalogTailRows(
                accountId = accountId,
                shopId = shopId,
                deviceId = deviceId,
                checkpoint = checkpoint,
                ids = ids,
                generationId = generationId,
                stagingDb = stagingDb,
                stagingRepository = stagingRepository,
                transferBudget = transferBudget
            )
            SyncEventDomains.PRICES -> {
                val prices = fetchTailRowsByIds(
                    accountId = accountId,
                    shopId = shopId,
                    deviceId = deviceId,
                    checkpoint = checkpoint,
                    domain = ShopSyncRowDomain.PRICES,
                    ids = ids.priceIds,
                    transferBudget = transferBudget
                ) as ShopSyncRows.Prices
                stageCatalogTailRows(
                    accountId = accountId,
                    shopId = shopId,
                    deviceId = deviceId,
                    checkpoint = checkpoint,
                    ids = SyncEventEntityIds(
                        productIds = prices.values.map { it.productId }.distinct()
                    ),
                    generationId = generationId,
                    stagingDb = stagingDb,
                    stagingRepository = stagingRepository,
                    transferBudget = transferBudget
                )
                stageTailRows(
                    stagingDb = stagingDb,
                    stagingRepository = stagingRepository,
                    generationId = generationId,
                    rows = prices
                )
            }
            SyncEventDomains.HISTORY -> stageTailRows(
                stagingDb = stagingDb,
                stagingRepository = stagingRepository,
                generationId = generationId,
                rows = fetchTailRowsByIds(
                    accountId = accountId,
                    shopId = shopId,
                    deviceId = deviceId,
                    checkpoint = checkpoint,
                    domain = ShopSyncRowDomain.HISTORY,
                    ids = ids.sessionIds,
                    transferBudget = transferBudget
                )
            )
            else -> throw ShopSyncContractException("recovery_tail_domain_unsupported")
        }
    }

    private suspend fun stageCatalogTailRows(
        accountId: String,
        shopId: String,
        deviceId: String,
        checkpoint: ShopSyncRecoveryCheckpoint,
        ids: SyncEventEntityIds,
        generationId: String,
        stagingDb: AppDatabase,
        stagingRepository: DefaultInventoryRepository,
        transferBudget: RecoveryTransferBudget
    ) {
        val initialSuppliers = ids.supplierIds.takeIf { it.isNotEmpty() }?.let {
            fetchTailRowsByIds(
                accountId, shopId, deviceId, checkpoint, ShopSyncRowDomain.SUPPLIERS, it,
                transferBudget
            ) as ShopSyncRows.Suppliers
        } ?: ShopSyncRows.Suppliers(emptyList())
        val initialCategories = ids.categoryIds.takeIf { it.isNotEmpty() }?.let {
            fetchTailRowsByIds(
                accountId, shopId, deviceId, checkpoint, ShopSyncRowDomain.CATEGORIES, it,
                transferBudget
            ) as ShopSyncRows.Categories
        } ?: ShopSyncRows.Categories(emptyList())
        val products = ids.productIds.takeIf { it.isNotEmpty() }?.let {
            fetchTailRowsByIds(
                accountId, shopId, deviceId, checkpoint, ShopSyncRowDomain.PRODUCTS, it,
                transferBudget
            ) as ShopSyncRows.Products
        } ?: ShopSyncRows.Products(emptyList())

        val parentSupplierIds = products.values
            .filter { it.deletedAt == null }
            .mapNotNull { it.supplierId }
            .filterNot { candidate -> initialSuppliers.values.any { it.id.equals(candidate, true) } }
            .distinct()
        val parentCategoryIds = products.values
            .filter { it.deletedAt == null }
            .mapNotNull { it.categoryId }
            .filterNot { candidate -> initialCategories.values.any { it.id.equals(candidate, true) } }
            .distinct()
        val parentSuppliers = parentSupplierIds.takeIf { it.isNotEmpty() }?.let {
            fetchTailRowsByIds(
                accountId, shopId, deviceId, checkpoint, ShopSyncRowDomain.SUPPLIERS, it,
                transferBudget
            ) as ShopSyncRows.Suppliers
        } ?: ShopSyncRows.Suppliers(emptyList())
        val parentCategories = parentCategoryIds.takeIf { it.isNotEmpty() }?.let {
            fetchTailRowsByIds(
                accountId, shopId, deviceId, checkpoint, ShopSyncRowDomain.CATEGORIES, it,
                transferBudget
            ) as ShopSyncRows.Categories
        } ?: ShopSyncRows.Categories(emptyList())

        val suppliers = ShopSyncRows.Suppliers(
            (initialSuppliers.values + parentSuppliers.values)
                .distinctBy { it.id.lowercase() }
        )
        val categories = ShopSyncRows.Categories(
            (initialCategories.values + parentCategories.values)
                .distinctBy { it.id.lowercase() }
        )
        stageTailRows(stagingDb, stagingRepository, generationId, suppliers)
        stageTailRows(stagingDb, stagingRepository, generationId, categories)
        stageTailRows(stagingDb, stagingRepository, generationId, products)
        stageImageTailMetadata(
            accountId = accountId,
            shopId = shopId,
            deviceId = deviceId,
            checkpoint = checkpoint,
            products = products.values,
            generationId = generationId,
            stagingDb = stagingDb,
            stagingRepository = stagingRepository,
            transferBudget = transferBudget
        )
    }

    private suspend fun stageImageTailMetadata(
        accountId: String,
        shopId: String,
        deviceId: String,
        checkpoint: ShopSyncRecoveryCheckpoint,
        products: List<InventoryProductRow>,
        generationId: String,
        stagingDb: AppDatabase,
        stagingRepository: DefaultInventoryRepository,
        transferBudget: RecoveryTransferBudget
    ) {
        if (products.isEmpty()) return
        val manifestDao = stagingDb.syncRecoveryManifestDao()
        val removedImageProducts = products
            .filter { it.deletedAt == null && it.primaryImageVersionId == null }
            .map { canonicalRecoveryEntityUuid(it.id) }
            .distinct()
        removedImageProducts.chunked(RECOVERY_TAIL_MANIFEST_DELETE_CHUNK).forEach { chunk ->
            manifestDao.deleteByRemoteIds(
                generationId = generationId,
                domain = ShopSyncRowDomain.IMAGES.wireValue,
                remoteIds = chunk
            )
        }
        // A product tombstone with an existing primary image is represented by
        // an image-domain tombstone in V6. Fetch it only when the A staging
        // manifest proves an image existed; a product that never had one has
        // no image row to request and must not be turned into a false gap.
        val tombstonedImageProducts = buildList {
            products.filter { it.deletedAt != null }.forEach { product ->
                val productId = canonicalRecoveryEntityUuid(product.id)
                val previous = manifestDao.get(
                    generationId = generationId,
                    domain = ShopSyncRowDomain.IMAGES.wireValue,
                    remoteId = productId
                )
                if (previous?.active == true) add(productId)
            }
        }
        val productIdsWithImage = (
            products
                .filter { it.deletedAt == null && it.primaryImageVersionId != null }
                .map { canonicalRecoveryEntityUuid(it.id) } + tombstonedImageProducts
            ).distinct()
        if (productIdsWithImage.isEmpty()) return
        val images = fetchTailRowsByIds(
            accountId = accountId,
            shopId = shopId,
            deviceId = deviceId,
            checkpoint = checkpoint,
            domain = ShopSyncRowDomain.IMAGES,
            ids = productIdsWithImage,
            transferBudget = transferBudget
        ) as ShopSyncRows.Images
        val productsById = products.associateBy { canonicalRecoveryEntityUuid(it.id) }
        images.values.forEach { image ->
            val product = productsById[canonicalRecoveryEntityUuid(image.productId)]
                ?: throw ShopSyncContractException("recovery_tail_image_product_missing")
            val validImageReceipt = if (product.deletedAt != null) {
                image.productDeletedAt == product.deletedAt
            } else {
                product.primaryImageVersionId?.lowercase() == image.versionId.lowercase() &&
                    image.productDeletedAt == null
            }
            if (!validImageReceipt) {
                throw ShopSyncContractException("recovery_tail_image_product_mismatch")
            }
        }
        stageTailRows(stagingDb, stagingRepository, generationId, images)
    }

    private suspend fun fetchTailRowsByIds(
        accountId: String,
        shopId: String,
        deviceId: String,
        checkpoint: ShopSyncRecoveryCheckpoint,
        domain: ShopSyncRowDomain,
        ids: List<String>,
        transferBudget: RecoveryTransferBudget
    ): ShopSyncRows {
        val normalizedIds = ids.map { canonicalRecoveryEntityUuid(it) }.distinct()
        if (normalizedIds.isEmpty()) {
            throw ShopSyncContractException("recovery_tail_targeted_ids_empty")
        }
        val context = ShopSyncRpcContext(
            accountId = accountId,
            shopId = shopId,
            deviceIdentifier = deviceId,
            expectedScope = checkpoint.scope,
            expectedEventMaxId = checkpoint.syncEvents.maxId,
            expectedDomainEventMaxId = checkpoint.syncEvents.domainMaxIds[domain.syncEventDomain()]
                ?: throw ShopSyncContractException("recovery_tail_domain_fence_missing")
        )
        val responses = normalizedIds.chunked(resourceLimits.targetedRows(domain)).map { chunk ->
            if (!leaseStillValid(accountId, shopId, deviceId)) {
                throw ShopSyncContractException("recovery_lease_invalid_before_tail_targeted")
            }
            val result = remote.rowsByIds(context, domain, chunk).getOrThrow()
            if (!leaseStillValid(accountId, shopId, deviceId)) {
                throw ShopSyncContractException("recovery_lease_invalid_after_tail_targeted")
            }
            validateTailTargetedRows(result, checkpoint, domain, shopId, chunk)
            transferBudget.recordTailTargetedResponse(
                domain = domain,
                responseBytes = result.responseBytes,
                largestRowBytes = result.largestRowBytes
            )
            result.rows
        }
        return when (domain) {
            ShopSyncRowDomain.SUPPLIERS -> ShopSyncRows.Suppliers(
                responses.flatMap { (it as? ShopSyncRows.Suppliers)?.values
                    ?: throw ShopSyncContractException("recovery_tail_targeted_domain_mismatch") }
            )
            ShopSyncRowDomain.CATEGORIES -> ShopSyncRows.Categories(
                responses.flatMap { (it as? ShopSyncRows.Categories)?.values
                    ?: throw ShopSyncContractException("recovery_tail_targeted_domain_mismatch") }
            )
            ShopSyncRowDomain.PRODUCTS -> ShopSyncRows.Products(
                responses.flatMap { (it as? ShopSyncRows.Products)?.values
                    ?: throw ShopSyncContractException("recovery_tail_targeted_domain_mismatch") }
            )
            ShopSyncRowDomain.PRICES -> ShopSyncRows.Prices(
                responses.flatMap { (it as? ShopSyncRows.Prices)?.values
                    ?: throw ShopSyncContractException("recovery_tail_targeted_domain_mismatch") }
            )
            ShopSyncRowDomain.HISTORY -> ShopSyncRows.History(
                responses.flatMap { (it as? ShopSyncRows.History)?.values
                    ?: throw ShopSyncContractException("recovery_tail_targeted_domain_mismatch") }
            )
            ShopSyncRowDomain.IMAGES -> ShopSyncRows.Images(
                responses.flatMap { (it as? ShopSyncRows.Images)?.values
                    ?: throw ShopSyncContractException("recovery_tail_targeted_domain_mismatch") }
            )
        }
    }

    private suspend fun stageTailRows(
        stagingDb: AppDatabase,
        stagingRepository: DefaultInventoryRepository,
        generationId: String,
        rows: ShopSyncRows
    ) {
        if (rows.size == 0) return
        val materializableRows = materializableRecoveryRows(stagingDb, generationId, rows)
        val applied = stagingRepository.applyShopSyncRecoveryRows(materializableRows)
        if (applied.skippedParentRows != 0 || applied.failedRows != 0 || applied.unsupportedRows != 0) {
            throw ShopSyncContractException("recovery_tail_stage_apply_incomplete")
        }
        if (rows is ShopSyncRows.History) {
            materializeTailHistoryTombstones(stagingDb, rows)
        }
        stagingDb.syncRecoveryManifestDao().upsertAll(
            rows.toManifestRows(generationId, rows.recoveryDomain())
        )
    }

    /**
     * A tail history tombstone is first decoded by the normal inbound path so
     * its scope/payload rules stay centralized. A publishable full-recovery
     * generation, however, contains physical active rows only while the
     * manifest retains tombstones for the B receipt. Remove the staged row and
     * its cascading bridge here; this method is never called for [activeDb].
     */
    private suspend fun materializeTailHistoryTombstones(
        stagingDb: AppDatabase,
        rows: ShopSyncRows.History
    ) {
        val remoteIds = rows.values
            .filter { it.deletedAt != null }
            .map { canonicalRecoveryEntityUuid(it.remoteId) }
            .distinct()
        if (remoteIds.isEmpty()) return
        stagingDb.withTransaction {
            remoteIds.forEach { remoteId ->
                val bridge = stagingDb.historyEntryRemoteRefDao().getByRemoteId(remoteId)
                    ?: return@forEach
                if (stagingDb.historyEntryDao().deleteByUid(bridge.historyEntryUid) != 1) {
                    throw ShopSyncContractException("recovery_tail_history_tombstone_delete_failed")
                }
            }
        }
    }

    private fun enforceGenerationStorageBudget(
        stagingFile: File,
        activationReady: Boolean,
        sameScopeOverlay: Boolean = false
    ) {
        val generationBytes = generationSizeBytes(stagingFile)
        if (generationBytes < 0L || generationBytes > resourceLimits.generationBytes) {
            throw ShopSyncContractException("recovery_generation_disk_budget_exceeded")
        }
        val headroom = if (activationReady) {
            val activePath = activeDb.openHelper.writableDatabase.path
                ?: throw ShopSyncContractException("recovery_active_database_path_invalid")
            if (activePath.isBlank() || activePath == ":memory:") {
                throw ShopSyncContractException("recovery_active_database_path_invalid")
            }
            val activeBytes = activeGenerationSizeBytes(File(activePath))
            val transactionHeadroom = requiredRecoveryActivationHeadroomBytes(
                stagingGenerationBytes = generationBytes,
                activeGenerationBytes = activeBytes,
                fixedHeadroomBytes = resourceLimits.activationHeadroomBytes
            )
            if (sameScopeOverlay) {
                // Source TEMP rows and old intent/ref projections may spill to
                // disk in addition to the normal main rollback/WAL headroom.
                checkedRecoveryAdd(transactionHeadroom,
                    checkedRecoveryAdd(generationBytes, activeBytes, "recovery_activation_headroom_overflow"),
                    "recovery_activation_headroom_overflow")
            } else transactionHeadroom
        } else {
            resourceLimits.activationHeadroomBytes
        }
        val directory = stagingFile.parentFile
            ?: throw ShopSyncContractException("recovery_staging_path_invalid")
        val available = availableStorageBytes(directory)
        if (available < 0L || available < headroom) {
            throw ShopSyncContractException("recovery_activation_headroom_insufficient")
        }
    }

    private suspend fun downloadDomain(
        accountId: String,
        shopId: String,
        deviceId: String,
        checkpoint: ShopSyncRecoveryCheckpoint,
        generationId: String,
        domain: ShopSyncRowDomain,
        stagingDb: AppDatabase,
        stagingRepository: DefaultInventoryRepository,
        stagingFile: File,
        transferBudget: RecoveryTransferBudget
    ) {
        val progress = RecoveryPageProgress(stagingDb.openHelper.writableDatabase)
        val accepted = progress.accepted(domain)
        if (accepted?.complete == true) return
        var afterId: String? = accepted?.afterId
        var pages = accepted?.pages ?: 0L
        val pageLimit = resourceLimits.pageRows(domain)
        // Recovery pages are lower-bound live reads, not a historical `as of
        // A` snapshot. They can therefore legitimately contain rows from B
        // (including additions) or omit rows that B will tombstone. Bound the
        // staging transfer by the device resource envelope and defer exact
        // receipt counts/digests to the post-tail B validation.
        val maximumPages = resourceLimits.rows(domain) / pageLimit.toLong() + 2L
        do {
            coroutineContext.ensureActive()
            if (pages >= maximumPages) {
                throw ShopSyncContractException("recovery_page_bound_exceeded")
            }
            if (!leaseStillValid(accountId, shopId, deviceId)) {
                throw ShopSyncContractException("recovery_lease_invalid_before_page")
            }
            val page = remote.recoveryPage(
                context = ShopSyncRpcContext(
                    accountId = accountId,
                    shopId = shopId,
                    deviceIdentifier = deviceId,
                    expectedScope = checkpoint.scope,
                    expectedEventMaxId = checkpoint.syncEvents.maxId,
                    expectedDomainEventMaxId = checkpoint.syncEvents.domainMaxIds.getValue(
                        domain.syncEventDomain()
                    )
                ),
                domain = domain,
                afterId = afterId,
                limit = pageLimit
            ).getOrThrow()
            if (!leaseStillValid(accountId, shopId, deviceId)) {
                throw ShopSyncContractException("recovery_lease_invalid_after_page")
            }
            validateLiveRecoveryPage(page, checkpoint, domain, shopId, afterId, pageLimit)
            transferBudget.record(
                domain = domain,
                pageBytes = page.responseBytes,
                largestRowBytes = page.largestRowBytes
            )
            val manifestRows = page.rows.toManifestRows(generationId, domain)
            transferBudget.recordRows(domain, manifestRows.size)
            stagingDb.withTransaction {
            if (manifestRows.isNotEmpty()) {
                stagingDb.syncRecoveryManifestDao().insertAll(manifestRows)
            }
            val materializableRows = materializableRecoveryRows(stagingDb, generationId, page.rows)
            val apply = stagingRepository.applyShopSyncRecoveryRows(materializableRows)
            val activeRows = if (materializableRows is ShopSyncRows.Prices) {
                materializableRows.size
            } else {
                manifestRows.count { it.active }
            }
            if (
                apply.businessRowsApplied != activeRows &&
                domain != ShopSyncRowDomain.IMAGES
            ) {
                throw ShopSyncContractException("recovery_stage_apply_count_mismatch")
            }
            if (apply.skippedParentRows != 0 || apply.failedRows != 0 || apply.unsupportedRows != 0) {
                throw ShopSyncContractException("recovery_stage_apply_incomplete")
            }
            ShopSyncRecoveryTestHooks.afterPageMaterializedBeforeCommit?.invoke(domain)
            progress.accept(domain, page)
            }
            enforceGenerationStorageBudget(stagingFile, activationReady = false)
            pages++
            afterId = page.nextAfterId
            if (page.hasMore && page.rows.size == 0) {
                throw ShopSyncContractException("recovery_page_stalled")
            }
        } while (page.hasMore)
    }

    private fun validateLiveRecoveryPage(
        page: ShopSyncRecoveryPage,
        checkpoint: ShopSyncRecoveryCheckpoint,
        domain: ShopSyncRowDomain,
        shopId: String,
        afterId: String?,
        pageLimit: Int
    ) {
        val expectedDomainFence = checkpoint.syncEvents.domainMaxIds[domain.syncEventDomain()]
            ?: throw ShopSyncContractException("recovery_page_domain_fence_missing")
        val expectedScopeKind = if (domain == ShopSyncRowDomain.HISTORY) {
            checkpoint.scope.historyKind ?: checkpoint.scope.kind
        } else {
            checkpoint.scope.kind
        }
        val rowIds = page.rows.ids().map(::canonicalRecoveryEntityUuid)
        val canonicalAfter = afterId?.let(::canonicalRecoveryEntityUuid)
        val rowsOrdered = rowIds.zipWithNext().all { (left, right) -> left < right }
        val cursorMatches = when {
            page.hasMore ->
                page.nextAfterId != null &&
                    canonicalRecoveryEntityUuid(page.nextAfterId) == rowIds.lastOrNull()
            else -> page.nextAfterId == null
        }
        if (
            page.schemaVersion != RECOVERY_PAGE_SCHEMA ||
            page.shopId.lowercase() != shopId.lowercase() ||
            page.scope != checkpoint.scope ||
            page.domain != domain ||
            page.snapshotEventMaxId != checkpoint.syncEvents.maxId ||
            parseShopSyncMaxEventId(page.currentScopeEventMaxId) <
                parseShopSyncMaxEventId(checkpoint.syncEvents.maxId) ||
            page.baselineDomainEventMaxId != expectedDomainFence ||
            parseShopSyncMaxEventId(page.pageDomainEventMaxId) <
                parseShopSyncMaxEventId(expectedDomainFence) ||
            page.domainScope != expectedScopeKind ||
            page.pageLimit != pageLimit ||
            page.rows.size > pageLimit ||
            rowIds.distinct().size != rowIds.size ||
            !rowsOrdered ||
            (canonicalAfter != null && rowIds.any { it <= canonicalAfter }) ||
            !cursorMatches ||
            !tailRowsMatchScope(page.rows, expectedScopeKind, checkpoint.scope, shopId)
        ) {
            throw ShopSyncContractException("recovery_page_contract_mismatch")
        }
    }

    private suspend fun activateAtomically(
        accountId: String,
        shopId: String,
        activeScope: Task126OwnerStoreScope,
        deviceId: String,
        checkpoint: ShopSyncRecoveryCheckpoint,
        runId: String,
        stagingName: String,
        stagingDb: AppDatabase,
        replaceConfirmed: Boolean
    ) {
        val mainSql = activeDb.openHelper.writableDatabase
        val sameScope = !replaceConfirmed
        if (sameScope) {
            val binding = activeDb.businessDataScopeBindingDao().get()?.toOwnerStoreScope()
            if (binding == null || Task126OwnerStoreGate.validate(binding, activeScope) != Task126OwnerStoreGateDecision.Allowed) {
                throw ShopSyncContractException("recovery_overlay_binding_mismatch")
            }
            if (activeDb.syncEventOutboxDao().countAll() !=
                activeDb.syncEventOutboxDao().countPendingForScope(accountId, activeScope.storeId)) {
                throw ShopSyncContractException("recovery_overlay_outbox_scope_mismatch")
            }
            val path = stagingDb.openHelper.readableDatabase.path
                ?: throw ShopSyncContractException("recovery_overlay_staging_path_missing")
            if (File(path).canonicalFile != validatedStagingFile(stagingName)?.canonicalFile) {
                throw ShopSyncContractException("recovery_overlay_staging_path_invalid")
            }
        }
        activeDb.withTransaction {
            if (!leaseStillValid(accountId, shopId, deviceId)) {
                throw ShopSyncContractException("recovery_lease_invalid_in_activation")
            }
            val deviceBefore = activeDb.syncEventDeviceStateDao().get()
                ?: throw ShopSyncContractException("recovery_device_missing_in_activation")
            if (deviceBefore.deviceId != deviceId) {
                throw ShopSyncContractException("recovery_device_changed_in_activation")
            }
            val journal = activeDb.syncRecoveryJournalDao().get()
                ?: throw ShopSyncContractException("recovery_journal_missing_in_activation")
            if (
                journal.phase != SyncRecoveryJournalPhases.READY_TO_ACTIVATE ||
                journal.ownerHash != activeScope.ownerHash ||
                journal.storeScope != activeScope.storeId ||
                journal.shopId?.lowercase() != shopId ||
                journal.deviceId != deviceId ||
                journal.runId != runId ||
                journal.stagingDatabaseName != stagingName ||
                journal.checkpointBDigest != checkpoint.checkpointDigest
            ) {
                throw ShopSyncContractException("recovery_journal_cas_failed")
            }
            val stageSql = stagingDb.openHelper.readableDatabase
            // Keep all reads/joins on the live Room writer without ATTACH, which
            // Android disables WAL for and whose aliases are connection-local.
            // Only transaction-local TEMP tables are added, streamed with one
            // prepared insert per table; no durable schema or second store.
            val sourceTables = if (sameScope) copyRecoverySourceToTemp(stageSql, mainSql) else emptyList()
            val overlay = if (sameScope) SameScopeRecoveryOverlay(mainSql).also { it.capture() } else null
            BUSINESS_DELETE_ORDER.forEach { mainSql.execSQL("DELETE FROM `$it`") }
            BUSINESS_COPY_ORDER.forEach { spec ->
                if (overlay != null) overlay.copyRemoteTable(spec.table) else copyTable(stageSql, mainSql, spec)
                ShopSyncRecoveryTestHooks.afterActiveTableCopied?.invoke(spec.table)
            }
            if (overlay != null) overlay.restorePending()
            else {
                mainSql.execSQL("DELETE FROM `pending_catalog_tombstones`")
                mainSql.execSQL("DELETE FROM `sync_event_outbox`")
            }
            mainSql.execSQL("DELETE FROM `sync_event_apply_status`")
            mainSql.execSQL("DELETE FROM `sync_event_watermarks`")
            mainSql.execSQL("DELETE FROM `business_data_scope_binding`")
            mainSql.execSQL("DELETE FROM `sync_recovery_baseline`")
            mainSql.execSQL("DELETE FROM `sync_recovery_manifest`")
            if (sameScope) {
                for (spec in listOf(BASELINE_TABLE, MANIFEST_TABLE)) {
                    val quoted = spec.columns.joinToString(",") { "`$it`" }
                    mainSql.execSQL("INSERT INTO `${spec.table}` ($quoted) SELECT $quoted FROM temp.`mc_recovery_source_${spec.table}`")
                }
            } else {
                copyTable(stageSql, mainSql, BASELINE_TABLE)
                copyTable(stageSql, mainSql, MANIFEST_TABLE)
            }
            ShopSyncRecoveryTestHooks.beforeActivationMetadata?.invoke()
            activeDb.businessDataScopeBindingDao().upsert(
                BusinessDataScopeBinding.from(activeScope, nowMs())
            )
            activeDb.syncEventWatermarkDao().upsert(
                SyncEventWatermark(
                    ownerUserId = accountId,
                    storeScope = activeScope.storeId,
                    lastSyncEventId = parseShopSyncMaxEventId(checkpoint.syncEvents.maxId)
                )
            )
            activeDb.syncRecoveryJournalDao().upsert(
                journal.copy(
                    phase = SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING,
                    updatedAtMs = nowMs(),
                    nextRetryAtMs = null
                )
            )
            mainSql.query("PRAGMA foreign_key_check").use { cursor ->
                if (cursor.moveToFirst()) {
                    throw ShopSyncContractException("recovery_activation_foreign_key_violation")
                }
            }
            (if (sameScope) listOf(BASELINE_TABLE, MANIFEST_TABLE) else
                BUSINESS_COPY_ORDER + BASELINE_TABLE + MANIFEST_TABLE).forEach { spec ->
                val sourceCount = if (sameScope) mainSql.query("SELECT COUNT(*) FROM temp.`mc_recovery_source_${spec.table}`").use { c ->
                    c.moveToFirst(); c.getLong(0)
                } else queryCount(stageSql, spec.table)
                if (queryCount(mainSql, spec.table) != sourceCount) {
                    throw ShopSyncContractException("recovery_activation_count_mismatch_${spec.table}")
                }
            }
            val deviceAfter = activeDb.syncEventDeviceStateDao().get()
            if (deviceAfter != deviceBefore) {
                throw ShopSyncContractException("recovery_device_mutated")
            }
            if (!leaseStillValid(accountId, shopId, deviceId)) {
                throw ShopSyncContractException("recovery_lease_invalid_before_commit")
            }
            overlay?.verifyPreservedIntent()
            overlay?.close()
            sourceTables.asReversed().forEach { mainSql.execSQL("DROP TABLE `$it`") }
        }
    }

    private suspend fun leaseStillValid(
        accountId: String,
        shopId: String,
        deviceId: String
    ): Boolean = scopeStillValid(accountId, shopId) &&
        activeDb.syncEventDeviceStateDao().get()?.deviceId == deviceId

    private suspend fun hasPendingWorkBeforeWholeStoreReplacement(): Boolean {
        // L'activation sostituisce l'unico store fisico e cancella l'outbox
        // intera. Una entry foreign-scope e' quindi corruzione/blocco, non lavoro
        // che possa essere ignorato dal gate e poi eliminato implicitamente.
        if (activeDb.syncEventOutboxDao().countAll() > 0) return true
        if (activeDb.pendingCatalogTombstoneDao().count() > 0) return true
        if (activeDb.supplierRemoteRefDao().hasPendingWork()) return true
        if (activeDb.categoryRemoteRefDao().hasPendingWork()) return true
        if (activeDb.productRemoteRefDao().hasPendingWork()) return true
        if (activeDb.productPriceDao().countPriceRowsPendingPriceBridge() > 0) return true
        if (activeDb.productPriceDao().countPriceRowsWithoutProductRemote() > 0) return true
        if (activeDb.supplierRemoteRefDao().countLocalRowsMissingRemoteRef() > 0) return true
        if (activeDb.categoryRemoteRefDao().countLocalRowsMissingRemoteRef() > 0) return true
        if (activeDb.productRemoteRefDao().countLocalRowsMissingRemoteRef() > 0) return true
        return activeDb.historyEntryDao().getUserVisibleSessionPushCandidateUids().isNotEmpty()
    }

    private suspend fun clearCleanupJournal(
        accountId: String,
        shopId: String,
        activeScope: Task126OwnerStoreScope,
        deviceId: String,
        generationId: String
    ) {
        activeDb.withTransaction {
            if (!leaseStillValid(accountId, shopId, deviceId)) {
                throw ShopSyncContractException("recovery_cleanup_lease_invalid")
            }
            val journal = activeDb.syncRecoveryJournalDao().get()
                ?: throw ShopSyncContractException("recovery_cleanup_journal_missing")
            val baseline = activeDb.syncRecoveryBaselineDao().get()
                ?: throw ShopSyncContractException("recovery_cleanup_baseline_missing")
            if (
                journal.phase != SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING ||
                journal.ownerHash != activeScope.ownerHash ||
                journal.storeScope != activeScope.storeId ||
                journal.deviceId != deviceId ||
                journal.runId != generationId ||
                baseline.generationId != generationId ||
                baseline.ownerHash != activeScope.ownerHash ||
                baseline.storeScope != activeScope.storeId ||
                baseline.deviceId != deviceId
            ) {
                throw ShopSyncContractException("recovery_cleanup_cas_failed")
            }
            if (journal.authorizationMode != SyncRecoveryAuthorizationModes.SAME_SCOPE &&
                hasPendingWorkBeforeWholeStoreReplacement()) {
                throw ShopSyncContractException("recovery_cleanup_pending_work")
            }
            if (activeDb.syncEventOutboxDao().countAll() !=
                activeDb.syncEventOutboxDao().countPendingForScope(accountId, activeScope.storeId)) {
                throw ShopSyncContractException("recovery_cleanup_outbox_scope_mismatch")
            }
            activeDb.syncRecoveryJournalDao().deleteAll()
            if (activeDb.syncRecoveryJournalDao().get() != null) {
                throw ShopSyncContractException("recovery_cleanup_journal_not_cleared")
            }
            if (!leaseStillValid(accountId, shopId, deviceId)) {
                throw ShopSyncContractException("recovery_cleanup_lease_invalid")
            }
        }
    }

    private suspend fun retryAfterFailure(
        journal: SyncRecoveryJournal,
        code: String,
        keepActivatedPhase: Boolean,
        expectedRunId: String?,
        retainStagingDatabase: Boolean = keepActivatedPhase,
        checkpointADigest: String? = null
    ): ShopSyncRecoveryResult.RetryRequired {
        var persistedRetryAt: Long? = null
        var observedRetryJournal: SyncRecoveryJournal? = null
        ShopSyncRecoveryTestHooks.beforeRetryTransaction?.invoke()
        activeDb.withTransaction {
            val current = activeDb.syncRecoveryJournalDao().get()
            observedRetryJournal = current
            if (
                current == journal &&
                current.ownerHash == journal.ownerHash &&
                current.storeScope == journal.storeScope &&
                current.deviceId == journal.deviceId &&
                current.runId == expectedRunId
            ) {
                val nextAttempt = nextSyncRecoveryAttemptCount(current.attemptCount)
                val retryAt = nowMs() + retryBackoffMs(nextAttempt)
                ShopSyncRecoveryTestHooks.beforeRetryJournalPersisted?.invoke()
                if (activeDb.syncRecoveryJournalDao().get() == current) {
                    activeDb.syncRecoveryJournalDao().upsert(
                        current.copy(
                            phase = if (keepActivatedPhase) {
                                SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING
                            } else {
                                if (retainStagingDatabase && current.checkpointADigest != null)
                                    SyncRecoveryJournalPhases.STAGING else SyncRecoveryJournalPhases.REQUIRED
                            },
                            reason = code,
                            attemptCount = nextAttempt,
                            updatedAtMs = nowMs(),
                            nextRetryAtMs = retryAt,
                            checkpointADigest = checkpointADigest ?: current.checkpointADigest.takeIf { retainStagingDatabase },
                            checkpointBDigest = current.checkpointBDigest.takeIf {
                                keepActivatedPhase
                            },
                            stagingDatabaseName = current.stagingDatabaseName.takeIf {
                                retainStagingDatabase
                            }
                        )
                    )
                    persistedRetryAt = retryAt
                }
            }
        }
        val retrySource = observedRetryJournal?.takeIf {
            it.ownerHash == journal.ownerHash &&
                it.storeScope == journal.storeScope &&
                it.deviceId == journal.deviceId
        } ?: journal
        val observedNow = nowMs()
        val retryAt = persistedRetryAt
            ?: retrySource.nextRetryAtMs?.takeIf { it > observedNow }
            ?: (
                observedNow +
                    retryBackoffMs(nextSyncRecoveryAttemptCount(retrySource.attemptCount))
                )
        return ShopSyncRecoveryResult.RetryRequired(code, retryAt)
    }

    private suspend fun activationWasCommitted(
        journal: SyncRecoveryJournal,
        runId: String,
        activeScope: Task126OwnerStoreScope,
        deviceId: String
    ): Boolean = activeDb.withTransaction {
        val current = activeDb.syncRecoveryJournalDao().get()
        val baseline = activeDb.syncRecoveryBaselineDao().get()
        current != null &&
            current == journal &&
            current.phase == SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING &&
            current.runId == runId &&
            current.ownerHash == activeScope.ownerHash &&
            current.storeScope == activeScope.storeId &&
            current.deviceId == deviceId &&
            baseline != null &&
            baseline.generationId == runId &&
            baseline.ownerHash == activeScope.ownerHash &&
            baseline.storeScope == activeScope.storeId &&
            baseline.deviceId == deviceId
    }

    private suspend fun updateJournal(
        expected: SyncRecoveryJournal,
        next: SyncRecoveryJournal
    ) {
        activeDb.withTransaction {
            val current = activeDb.syncRecoveryJournalDao().get()
            if (current != expected) {
                throw ShopSyncContractException("recovery_journal_changed")
            }
            activeDb.syncRecoveryJournalDao().upsert(next)
        }
    }

    private suspend fun requireOwnedJournal(runId: String): SyncRecoveryJournal {
        val current = activeDb.syncRecoveryJournalDao().get()
            ?: throw ShopSyncContractException("recovery_journal_lost")
        if (current.runId != runId) {
            throw ShopSyncContractException("recovery_journal_changed")
        }
        return current
    }

    private fun openStagingDatabase(name: String): AppDatabase =
        Room.databaseBuilder(appContext, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

    private fun hasAcceptedRecoveryHeader(name: String, journal: SyncRecoveryJournal): Boolean {
        if (journal.stagingDatabaseName != name || validatedStagingFile(name)?.exists() != true) return false
        val candidate = openStagingDatabase(name)
        return try {
            RecoveryPageProgress(candidate.openHelper.writableDatabase).checkpoint(journal)
            true
        } catch (_: Exception) { false } finally { candidate.close() }
    }

    private fun cleanupKnownStaging(name: String?): Boolean {
        if (name == null) return true
        if (!isValidStagingName(name)) return false
        val file = validatedStagingFile(name) ?: return false
        try {
            deleteStagingDatabase(appContext, name)
        } catch (_: Exception) {
            logger("shop_sync_recovery staging_cleanup_deferred")
        }
        val candidates = listOf(
            file,
            File(file.path + "-wal"),
            File(file.path + "-shm"),
            File(file.path + "-journal")
        )
        candidates
            .forEach { candidate ->
                if (candidate.exists() && !candidate.delete()) {
                    logger("shop_sync_recovery staging_cleanup_deferred")
                }
            }
        return candidates.none(File::exists)
    }

    /**
     * Rimuove un numero limitato di generation orfane per invocazione. I nomi
     * vengono ricavati soltanto dalla directory Room e devono superare la stessa
     * allowlist UUID usata per creare lo staging; sidecar e file estranei non
     * vengono mai toccati. Se il limite viene raggiunto, il journal resta
     * durevole e il retry successivo prosegue il cleanup senza loop stretto.
     */
    private fun cleanupBoundedOrphanStaging(excluding: String? = null): Boolean {
        val names = existingStagingDatabaseNames().filter { it != excluding }
        var allDeleted = true
        names.take(MAX_ORPHAN_STAGING_CLEANUPS_PER_RUN).forEach { name ->
            if (!cleanupKnownStaging(name)) allDeleted = false
        }
        return allDeleted && existingStagingDatabaseNames().none { it != excluding }
    }

    private fun existingStagingDatabaseNames(): List<String> {
        val databaseRoot = appContext.getDatabasePath(".").canonicalFile
        return databaseRoot.listFiles()
            .orEmpty()
            .asSequence()
            .map { file ->
                KNOWN_SQLITE_SIDECAR_SUFFIXES.firstOrNull(file.name::endsWith)
                    ?.let { suffix -> file.name.removeSuffix(suffix) }
                    ?: file.name
            }
            .filter(::isValidStagingName)
            .distinct()
            .sorted()
            .toList()
    }

    private fun validatedStagingFile(name: String): File? {
        if (!isValidStagingName(name)) return null
        val candidate = appContext.getDatabasePath(name).canonicalFile
        val root = appContext.getDatabasePath(".").canonicalFile
        return candidate.takeIf { it.parentFile == root }
    }

    private fun isValidStagingName(name: String): Boolean =
        STAGING_NAME_PATTERN.matches(name)
}

/** The immutable full canonical A ledger remains mandatory even when acknowledged local bodies already reflect C. */
internal suspend fun validateShopSyncCanonicalReceipt(
    db: AppDatabase,
    generationId: String,
    checkpoint: ShopSyncRecoveryCheckpoint
) {
    if (checkpoint.schemaVersion != "shop-sync-recovery-checkpoint-v1" || checkpoint.status != "ready" ||
        checkpoint.integrity.totalViolationCount != 0L || checkpoint.syncEvents.requiresFullRecovery) {
        throw ShopSyncContractException("ordinary_canonical_receipt_invalid")
    }
    validateRelationalManifest(db, generationId)
    validateManifest(db, generationId, checkpoint)
    // Also prove every retained/active price parent in the same generation, without reading physical A bodies.
    materializablePriceCount(db, generationId)
}

/** Shared complete local proof; the active store deliberately retains History tombstone bodies. */
internal suspend fun validateShopSyncActiveReceipt(
    db: AppDatabase,
    generationId: String,
    checkpoint: ShopSyncRecoveryCheckpoint
) {
    validateHistoryTombstoneShadows(db, generationId)
    validateShopSyncCanonicalReceipt(db, generationId, checkpoint)
    validateStagingDatabase(db, generationId, checkpoint, activeStoreHistory = true, localAcknowledgements = true)
    validatePhysicalSnapshot(db, generationId, activeStoreHistory = true, localAcknowledgements = true)
}

private data class HistoryTombstoneShadow(val uid: Long, val remoteId: String, val deletedAt: String)

/** Scan every deleted physical row, including orphan bridges; never load its retained old body. */
private suspend fun validateHistoryTombstoneShadows(db: AppDatabase, generationId: String) {
    var afterUid: Long? = null
    do {
        coroutineContext.ensureActive()
        val rows = db.openHelper.readableDatabase.query(
            """
            SELECT h.uid, h.deletedAt, r.remoteId, r.localChangeRevision,
                   r.lastSyncedLocalRevision, r.lastRemoteAppliedAt, r.lastRemotePayloadFingerprint
            FROM history_entries h
            LEFT JOIN history_entry_remote_refs r ON r.historyEntryUid = h.uid
            WHERE h.deletedAt IS NOT NULL AND (? IS NULL OR h.uid > ?)
            ORDER BY h.uid LIMIT ?
            """.trimIndent(), arrayOf<Any?>(afterUid, afterUid, PHYSICAL_VERIFY_PAGE_SIZE)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.isNull(2) || cursor.isNull(3) || cursor.isNull(4) ||
                        cursor.isNull(5) || cursor.isNull(6) || cursor.getInt(3) != cursor.getInt(4)) {
                        throw ShopSyncContractException("ordinary_history_shadow_ref_invalid")
                    }
                    val remoteId = cursor.getString(2)
                    if (canonicalRecoveryEntityUuid(remoteId) != remoteId) {
                        throw ShopSyncContractException("ordinary_history_shadow_ref_invalid")
                    }
                    val deletedAt = cursor.getString(1)
                    if (!isCanonicalHistoryShadowUtc(deletedAt)) {
                        throw ShopSyncContractException("ordinary_history_shadow_tombstone_invalid")
                    }
                    add(HistoryTombstoneShadow(cursor.getLong(0), remoteId, deletedAt))
                }
            }
        }
        val manifests = db.syncRecoveryManifestDao().getByRemoteIds(
            generationId, ShopSyncRowDomain.HISTORY.wireValue, rows.map { it.remoteId }
        ).associateBy { it.remoteId }
        rows.forEach { shadow ->
            val manifest = manifests[shadow.remoteId]
                ?: throw ShopSyncContractException("ordinary_history_shadow_manifest_missing")
            val fields = manifest.versionLine.split('\u001f')
            if (manifest.active || manifest.payloadDigest != null || fields.size != 5 ||
                fields[0] != shadow.remoteId || fields[2] != shadow.deletedAt || fields[4] != "-" ||
                !isCanonicalHistoryShadowUtc(fields[1])) {
                throw ShopSyncContractException("ordinary_history_shadow_manifest_invalid")
            }
            historyManifestPayloadVersion(manifest)
        }
        afterUid = rows.lastOrNull()?.uid ?: afterUid
    } while (rows.size == PHYSICAL_VERIFY_PAGE_SIZE)
}

private fun isCanonicalHistoryShadowUtc(value: String): Boolean =
    CANONICAL_TIMESTAMP_PATTERN.matches(value) && !value.startsWith("0000-") &&
        runCatching { LocalDateTime.parse(value.removeSuffix("Z")) }.isSuccess

internal suspend fun validateManifest(
    db: AppDatabase,
    generationId: String,
    checkpoint: ShopSyncRecoveryCheckpoint
) {
    val calculated = linkedMapOf<ShopSyncRowDomain, ShopSyncDomainCheckpoint>()
    for (domain in ShopSyncRowDomain.entries) {
        var afterId: String? = null
        var active = 0L
        var tombstones = 0L
        val idDigest = CanonicalLineDigest()
        val versionDigest = CanonicalLineDigest()
        val identityDigest = CanonicalLineDigest()
        var identityPresent = false
        do {
            val rows = db.syncRecoveryManifestDao().page(
                generationId = generationId,
                domain = domain.wireValue,
                afterId = afterId,
                limit = MANIFEST_VERIFY_PAGE_SIZE
            )
            for (row in rows) {
                if (row.active) active++ else tombstones++
                // Every V6 domain, including images, publishes its id set
                // ordered by the manifest remote id. For images that id is
                // the scoped product id, never the image-version id.
                idDigest.add(row.idLine)
                versionDigest.add(row.versionLine)
                row.identityLine?.let {
                    identityDigest.add(it)
                    identityPresent = true
                }
            }
            afterId = rows.lastOrNull()?.remoteId
        } while (rows.size == MANIFEST_VERIFY_PAGE_SIZE)
        calculated[domain] = ShopSyncDomainCheckpoint(
            activeCount = active,
            tombstoneCount = tombstones,
            idSetDigest = idDigest.finish(),
            versionDigest = versionDigest.finish(),
            identityDigest = identityDigest.finish().takeIf {
                identityPresent || domain == ShopSyncRowDomain.PRODUCTS
            }
        )
        if (calculated.getValue(domain) != checkpoint.domain(domain)) {
            throw ShopSyncContractException("recovery_manifest_digest_mismatch_${domain.wireValue}")
        }
    }
    val catalogDigest = sha256(
        calculated.getValue(ShopSyncRowDomain.SUPPLIERS).versionDigest + "\n" +
            calculated.getValue(ShopSyncRowDomain.CATEGORIES).versionDigest + "\n" +
            calculated.getValue(ShopSyncRowDomain.PRODUCTS).versionDigest
    )
    if (catalogDigest != checkpoint.catalog.digest) {
        throw ShopSyncContractException("recovery_catalog_digest_mismatch")
    }
}

/**
 * Merge-join paginato tra manifest prodotti e immagini. Le relazioni
 * catalogo/prezzi attive sono inoltre provate dal readback Room e dalle FK;
 * qui copriamo anche tombstone e riferimenti immagine non materializzati.
 */
internal suspend fun validateRelationalManifest(db: AppDatabase, generationId: String) {
    val products = RecoveryManifestReader(
        dao = db.syncRecoveryManifestDao(),
        generationId = generationId,
        domain = ShopSyncRowDomain.PRODUCTS
    )
    val images = RecoveryManifestReader(
        dao = db.syncRecoveryManifestDao(),
        generationId = generationId,
        domain = ShopSyncRowDomain.IMAGES
    )
    var image = images.next()
    while (true) {
        val product = products.next() ?: break
        if (image != null && image.remoteId < product.remoteId) {
            throw ShopSyncContractException("recovery_image_product_invalid")
        }
        val primaryImage = productPrimaryImageVersion(product)
        if (primaryImage == null) {
            if (image?.remoteId == product.remoteId) {
                // V6 deliberately keeps an image-domain tombstone when a
                // deleted product used to have a primary image. It is
                // evidence for the image digest, never a live relation.
                if (
                    !product.active &&
                    !image.active &&
                    productDeletedAt(product) == imageProductDeletedAt(image)
                ) {
                    image = images.next()
                } else {
                    throw ShopSyncContractException("recovery_image_set_incomplete")
                }
            }
            continue
        }
        if (image?.remoteId != product.remoteId || imageVersionId(image) != primaryImage) {
            throw ShopSyncContractException("recovery_primary_image_invalid")
        }
        if (image.active != product.active) {
            throw ShopSyncContractException("recovery_image_product_state_mismatch")
        }
        image = images.next()
    }
    if (image != null) {
        throw ShopSyncContractException("recovery_image_product_invalid")
    }
}

/**
 * Rilegge le righe realmente materializzate in Room e le confronta con gli
 * hash del manifest remoto. Il manifest da solo non e' una prova di apply:
 * un mapper errato potrebbe infatti conservare digest perfetti ma pubblicare
 * colonne business diverse.
 */
internal suspend fun validatePhysicalSnapshot(
    db: AppDatabase,
    generationId: String,
    historyPageRows: Int = DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.historyPageRows,
    activeStoreHistory: Boolean = false,
    localOverlay: Boolean = false,
    localAcknowledgements: Boolean = false
) {
    val acknowledgedScope = if (localAcknowledgements) {
        requireNotNull(db.businessDataScopeBindingDao().get()) to
            requireNotNull(db.syncEventDeviceStateDao().get()).deviceId
    } else null
    listOf(
        ShopSyncRowDomain.SUPPLIERS,
        ShopSyncRowDomain.CATEGORIES,
        ShopSyncRowDomain.PRODUCTS,
        ShopSyncRowDomain.PRICES,
        ShopSyncRowDomain.HISTORY
    ).forEach { domain ->
        var afterId: String? = null
        val pageSize = if (domain == ShopSyncRowDomain.HISTORY) {
            historyPageRows
        } else {
            PHYSICAL_VERIFY_PAGE_SIZE
        }
        do {
            val expected = if (localAcknowledgements) {
                localAcknowledgedManifestPage(db, generationId, domain, afterId, pageSize)
            } else if (localOverlay) {
                cleanOverlayManifestPage(db, generationId, domain, afterId, pageSize)
            } else if (domain == ShopSyncRowDomain.PRICES) {
                materializablePriceManifestPage(db, generationId, afterId, pageSize)
            } else {
                db.syncRecoveryManifestDao().pageActive(
                    generationId = generationId,
                    domain = domain.wireValue,
                    afterId = afterId,
                    limit = pageSize
                )
            }
            val physical = readPhysicalPage(db, generationId, domain, afterId, pageSize, activeStoreHistory,
                localOverlay, localAcknowledgements)
            if (physical.size != expected.size) {
                throw ShopSyncContractException(
                    "recovery_physical_count_mismatch_${domain.wireValue}"
                )
            }
            expected.zip(physical).forEach body@ { (manifest, materialized) ->
                if (manifest.domain == LOCAL_ACK_BODY_PREFIX + domain.wireValue) {
                    val (binding, device) = requireNotNull(acknowledgedScope)
                    validateLocalAcknowledgedMaterialization(binding, device, manifest, materialized)
                    return@body
                }
                if (
                    manifest.remoteId != materialized.remoteId ||
                    manifest.payloadDigest == null ||
                    manifest.payloadDigest != materialized.payloadDigest ||
                    (materialized.versionLine != null &&
                        manifest.versionLine != materialized.versionLine)
                ) {
                    throw ShopSyncContractException(
                        "recovery_physical_digest_mismatch_${domain.wireValue}"
                    )
                }
                if (domain == ShopSyncRowDomain.HISTORY) {
                    validateHistoryPhysicalMaterialization(manifest, materialized)
                }
            }
            afterId = expected.lastOrNull()?.remoteId
        } while (expected.size == pageSize)
    }
}

/** Exempts only durable same-scope local intent; the complete canonical remote ledger remains unchanged. */
private fun overlayPendingPredicate(domain: ShopSyncRowDomain, remoteId: String, generationSql: String): String = when (domain) {
    ShopSyncRowDomain.SUPPLIERS, ShopSyncRowDomain.CATEGORIES, ShopSyncRowDomain.PRODUCTS -> {
        val (table, entity) = when (domain) {
            ShopSyncRowDomain.SUPPLIERS -> "supplier_remote_refs" to "SUPPLIER"
            ShopSyncRowDomain.CATEGORIES -> "category_remote_refs" to "CATEGORY"
            else -> "product_remote_refs" to "PRODUCT"
        }
        "EXISTS (SELECT 1 FROM $table lr WHERE lr.remoteId=$remoteId AND " +
            "(lr.lastRemoteAppliedAt IS NULL OR lr.localChangeRevision<>lr.lastSyncedLocalRevision)) OR " +
            "EXISTS (SELECT 1 FROM pending_catalog_tombstones lt WHERE lt.entityType='$entity' AND lt.remoteId=$remoteId)"
    }
    ShopSyncRowDomain.HISTORY -> "EXISTS (SELECT 1 FROM history_entry_remote_refs lr JOIN history_entries lh ON lh.uid=lr.historyEntryUid " +
        "WHERE lr.remoteId=$remoteId AND (lr.lastRemoteAppliedAt IS NULL OR lr.localChangeRevision<>lr.lastSyncedLocalRevision OR lh.syncStatus<>'SYNCED_SUCCESSFULLY'))"
    ShopSyncRowDomain.PRICES -> "EXISTS (SELECT 1 FROM sync_recovery_manifest lp JOIN pending_catalog_tombstones lt " +
        "ON lt.entityType='PRODUCT' AND lt.remoteId=substr(lp.versionLine, instr(substr(lp.versionLine,instr(lp.versionLine,char(31))+1),char(31))+instr(lp.versionLine,char(31))+1,36) " +
        "WHERE lp.generationId=$generationSql AND lp.domain='prices' AND lp.remoteId=$remoteId)"
    ShopSyncRowDomain.IMAGES -> "0"
}

private suspend fun cleanOverlayManifestPage(
    db: AppDatabase, generationId: String, domain: ShopSyncRowDomain, afterId: String?, limit: Int
): List<SyncRecoveryManifestRow> {
    val result = ArrayList<SyncRecoveryManifestRow>(limit)
    var cursor = afterId
    while (result.size < limit) {
        coroutineContext.ensureActive()
        val batchLimit = limit - result.size
        val rows = if (domain == ShopSyncRowDomain.PRICES) {
            materializablePriceManifestPage(db, generationId, cursor, batchLimit)
        } else db.syncRecoveryManifestDao().pageActive(generationId, domain.wireValue, cursor, batchLimit)
        if (rows.isEmpty()) break
        val placeholders = rows.joinToString(",") { "?" }
        val exempt = db.openHelper.readableDatabase.query(
            "SELECT m.remoteId FROM sync_recovery_manifest m WHERE m.generationId=? AND m.domain=? AND m.remoteId IN ($placeholders) AND (${overlayPendingPredicate(domain, "m.remoteId", "m.generationId")})",
            (listOf(generationId, domain.wireValue) + rows.map { it.remoteId }).toTypedArray()
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        result += rows.filter { it.remoteId !in exempt }
        cursor = rows.last().remoteId
        if (rows.size < batchLimit) break
    }
    return result
}

/** Merge two bounded keyset pages; actual ACK proofs replace only their same-ID historical body. */
private suspend fun localAcknowledgedManifestPage(
    db: AppDatabase, generationId: String, domain: ShopSyncRowDomain, afterId: String?, limit: Int
): List<SyncRecoveryManifestRow> {
    val result = ArrayList<SyncRecoveryManifestRow>(limit)
    var cursor = afterId
    while (result.size < limit) {
        coroutineContext.ensureActive()
        val bound = limit - result.size
        val canonical = if (domain == ShopSyncRowDomain.PRICES)
            materializablePriceManifestPage(db, generationId, cursor, bound)
            else db.syncRecoveryManifestDao().pageActive(generationId,domain.wireValue,cursor,bound)
        val acknowledged = db.syncRecoveryManifestDao().pageActive(
            generationId,LOCAL_ACK_BODY_PREFIX+domain.wireValue,cursor,bound)
        val merged = (canonical + acknowledged).associateBy { it.remoteId }.values.sortedBy { it.remoteId }.take(bound)
        if (merged.isEmpty()) break
        val exempt = db.openHelper.readableDatabase.query(
            "SELECT m.remoteId FROM sync_recovery_manifest m WHERE m.generationId=? AND m.domain IN (?,?) " +
                "AND m.remoteId IN (${merged.joinToString(",") { "?" }}) AND (${overlayPendingPredicate(domain, "m.remoteId", "m.generationId")})",
            (listOf(generationId,domain.wireValue,LOCAL_ACK_BODY_PREFIX+domain.wireValue)+merged.map { it.remoteId }).toTypedArray()
        ).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
        result += merged.filter { it.remoteId !in exempt }
        cursor=merged.last().remoteId
        if (merged.size<bound) break
    }
    return result
}

private fun validateLocalAcknowledgedMaterialization(
    binding: BusinessDataScopeBinding, device: String, proof: SyncRecoveryManifestRow, physical: PhysicalRecoveryRow
) {
    if (proof.versionLine.length>4096 || proof.idLine!=proof.remoteId || proof.remoteId!=physical.remoteId ||
        proof.payloadDigest==null || proof.payloadDigest!=physical.localAckFingerprint)
        throw ShopSyncContractException("local_ack_physical_body_mismatch")
    val identity=try { Json.decodeFromString<LocalAcknowledgedBodyIdentity>(proof.versionLine) }
        catch (_: Exception) { throw ShopSyncContractException("local_ack_identity_invalid") }
    if(!identity.matches(binding,device) || identity.revision!=physical.acknowledgedRevision)
        throw ShopSyncContractException("local_ack_scope_mismatch")
}

/**
 * Append-only prices remain in the complete receipt even after their
 * product is deleted. Only a product tombstone in this generation can
 * exempt a price from Room's required active-product relationship.
 */
internal suspend fun materializableRecoveryRows(
    db: AppDatabase,
    generationId: String,
    rows: ShopSyncRows
): ShopSyncRows {
    if (rows !is ShopSyncRows.Prices) return rows
    val materializable = ArrayList<InventoryProductPriceRow>(rows.size)
    for (batch in rows.values.chunked(PHYSICAL_VERIFY_PAGE_SIZE)) {
        val activeParents = activePriceParentIds(
            db,
            generationId,
            batch.map { canonicalRecoveryEntityUuid(it.productId) }.distinct()
        )
        materializable += batch.filter {
            canonicalRecoveryEntityUuid(it.productId) in activeParents
        }
    }
    return ShopSyncRows.Prices(materializable)
}

private suspend fun activePriceParentIds(
    db: AppDatabase,
    generationId: String,
    parentIds: List<String>
): Set<String> {
    if (parentIds.isEmpty()) return emptySet()
    if (parentIds.size > PHYSICAL_VERIFY_PAGE_SIZE) {
        throw ShopSyncContractException("recovery_price_parent_lookup_bound_exceeded")
    }
    val parents = db.syncRecoveryManifestDao()
        .getProductsByRemoteIds(generationId, parentIds)
        .associateBy { it.remoteId }
    return parentIds.filterTo(hashSetOf()) { parentId ->
        val parent = parents[parentId]
            ?: throw ShopSyncContractException("recovery_price_parent_manifest_missing")
        if (parent.active != (productDeletedAt(parent) == null)) {
            throw ShopSyncContractException("recovery_price_parent_manifest_invalid")
        }
        parent.active
    }
}

private fun priceManifestParentId(row: SyncRecoveryManifestRow): String {
    val fields = row.versionLine.split('\u001f')
    if (fields.size != 9 || fields[0] != row.remoteId) {
        throw ShopSyncContractException("recovery_price_parent_manifest_invalid")
    }
    val parentId = canonicalRecoveryEntityUuid(fields[2])
    if (parentId != fields[2]) {
        throw ShopSyncContractException("recovery_price_parent_manifest_invalid")
    }
    return parentId
}

/** Bounded keyset scan; neither the full price ledger nor all parents are loaded. */
private suspend fun materializablePriceManifestPage(
    db: AppDatabase,
    generationId: String,
    afterId: String?,
    limit: Int
): List<SyncRecoveryManifestRow> {
    val materializable = ArrayList<SyncRecoveryManifestRow>(limit)
    var cursor = afterId
    while (materializable.size < limit) {
        coroutineContext.ensureActive()
        val batchLimit = limit - materializable.size
        val rows = db.syncRecoveryManifestDao().pageActive(
            generationId,
            ShopSyncRowDomain.PRICES.wireValue,
            cursor,
            batchLimit
        )
        if (rows.isEmpty()) break
        val parentIds = rows.map(::priceManifestParentId)
        val activeParents = activePriceParentIds(db, generationId, parentIds.distinct())
        materializable += rows.filterIndexed { index, _ -> parentIds[index] in activeParents }
        cursor = rows.last().remoteId
        if (rows.size < batchLimit) break
    }
    return materializable
}

private suspend fun materializablePriceCount(db: AppDatabase, generationId: String): Long {
    var count = 0L
    var afterId: String? = null
    do {
        val rows = materializablePriceManifestPage(
            db,
            generationId,
            afterId,
            PHYSICAL_VERIFY_PAGE_SIZE
        )
        count = Math.addExact(count, rows.size.toLong())
        afterId = rows.lastOrNull()?.remoteId
    } while (rows.size == PHYSICAL_VERIFY_PAGE_SIZE)
    return count
}

private suspend fun readPhysicalPage(
    db: AppDatabase,
    generationId: String,
    domain: ShopSyncRowDomain,
    afterId: String?,
    limit: Int,
    activeStoreHistory: Boolean,
    localOverlay: Boolean = false,
    includeLocalAckFingerprints: Boolean = false
): List<PhysicalRecoveryRow> {
    if (domain == ShopSyncRowDomain.HISTORY) {
        val rows = if (activeStoreHistory) {
            db.historyEntryDao().getRecoveryActivePhysicalPage(afterId, limit, onlyClean = localOverlay)
        } else {
            db.historyEntryDao().getRecoveryPhysicalPage(afterId, limit, onlyClean = localOverlay)
        }
        return rows.map { row ->
            if (
                row.localRevision != row.syncedRevision ||
                row.payloadFingerprint == null
            ) {
                throw ShopSyncContractException("recovery_physical_history_dirty")
            }
            PhysicalRecoveryRow(
                remoteId = canonicalRecoveryEntityUuid(row.remoteId),
                payloadDigest = recoveryHistoryPayloadDigest(
                    physicalFingerprint = recoveryHistoryPhysicalFingerprint(row.entry),
                    sourcePayloadFingerprint = requireNotNull(row.payloadFingerprint)
                ),
                history = HistoryPhysicalRecoveryState(
                    entry = row.entry,
                    sourcePayloadFingerprint = requireNotNull(row.payloadFingerprint)
                ),
                localAckFingerprint = if (includeLocalAckFingerprints)
                    historyPhysicalPayloadFingerprint(row.entry, row.remoteId) else null,
                acknowledgedRevision = row.syncedRevision.toLong()
            )
        }
    }
    val sql = when (domain) {
        ShopSyncRowDomain.SUPPLIERS ->
            """
            SELECT r.remoteId, s.name, r.remoteUpdatedAt,
                   r.localChangeRevision, r.lastSyncedLocalRevision
            FROM supplier_remote_refs r
            INNER JOIN suppliers s ON s.id = r.supplierId
            """.trimIndent()
        ShopSyncRowDomain.CATEGORIES ->
            """
            SELECT r.remoteId, c.name, r.remoteUpdatedAt,
                   r.localChangeRevision, r.lastSyncedLocalRevision
            FROM category_remote_refs r
            INNER JOIN categories c ON c.id = r.categoryId
            """.trimIndent()
        ShopSyncRowDomain.PRODUCTS ->
            """
            SELECT r.remoteId, p.barcode, p.itemNumber, p.productName,
                   p.secondProductName, p.purchasePrice, p.retailPrice,
                   p.stockQuantity, p.primaryImageVersionId, p.primaryImageUpdatedAt,
                   sr.remoteId, cr.remoteId, r.remoteUpdatedAt,
                   r.localChangeRevision, r.lastSyncedLocalRevision
            FROM product_remote_refs r
            INNER JOIN products p ON p.id = r.productId
            LEFT JOIN supplier_remote_refs sr ON sr.supplierId = p.supplierId
            LEFT JOIN category_remote_refs cr ON cr.categoryId = p.categoryId
            """.trimIndent()
        ShopSyncRowDomain.PRICES ->
            """
            SELECT pr.remoteId, rr.remoteId, p.type, p.price, p.effectiveAt,
                   p.source, p.note, p.createdAt
            FROM product_price_remote_refs pr
            INNER JOIN product_prices p ON p.id = pr.productPriceId
            INNER JOIN product_remote_refs rr ON rr.productId = p.productId
            """.trimIndent()
        ShopSyncRowDomain.HISTORY, ShopSyncRowDomain.IMAGES ->
            throw ShopSyncContractException("recovery_physical_domain_invalid")
    }
    val args = buildList<Any> {
        if (afterId != null) add(afterId)
        if (localOverlay && domain == ShopSyncRowDomain.PRICES) add(generationId)
        add(limit)
    }.toTypedArray()
    val remoteAlias = if (domain == ShopSyncRowDomain.PRICES) "pr" else "r"
    val scopedSql = buildString {
        append(sql)
        val predicates = mutableListOf<String>()
        if (afterId != null) predicates += "$remoteAlias.remoteId > ?"
        if (localOverlay && domain != ShopSyncRowDomain.PRICES) {
            predicates += "r.lastRemoteAppliedAt IS NOT NULL AND r.localChangeRevision=r.lastSyncedLocalRevision"
        }
        if (localOverlay) predicates += "NOT (${overlayPendingPredicate(domain, "$remoteAlias.remoteId", "?")})"
        if (predicates.isNotEmpty()) append(" WHERE ${predicates.joinToString(" AND ")}")
        append(" ORDER BY $remoteAlias.remoteId LIMIT ?")
    }
    return db.openHelper.readableDatabase.query(scopedSql, args).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(physicalRow(domain, cursor, includeLocalAckFingerprints))
            }
        }
    }
}

private fun physicalRow(domain: ShopSyncRowDomain, cursor: Cursor, includeLocalAckFingerprints: Boolean): PhysicalRecoveryRow =
    when (domain) {
        ShopSyncRowDomain.SUPPLIERS -> {
            requireCleanPhysicalRef(cursor, 3, 4)
            val remoteId = canonicalRecoveryEntityUuid(cursor.getString(0))
            val updatedAt = canonicalTimestamp(cursor.nullableString(2))
            PhysicalRecoveryRow(
                remoteId = remoteId,
                payloadDigest = sha256(recoverySupplierPayloadFingerprint(cursor.getString(1))),
                versionLine = listOf(remoteId, updatedAt, "-").joinToString("\u001f"),
                localAckFingerprint = if (includeLocalAckFingerprints) fingerprintSupplierInbound(InventorySupplierRow(
                    id=remoteId,ownerUserId="",name=cursor.getString(1),updatedAt=cursor.nullableString(2))) else null,
                acknowledgedRevision = cursor.getLong(4)
            )
        }
        ShopSyncRowDomain.CATEGORIES -> {
            requireCleanPhysicalRef(cursor, 3, 4)
            val remoteId = canonicalRecoveryEntityUuid(cursor.getString(0))
            val updatedAt = canonicalTimestamp(cursor.nullableString(2))
            PhysicalRecoveryRow(
                remoteId = remoteId,
                payloadDigest = sha256(recoveryCategoryPayloadFingerprint(cursor.getString(1))),
                versionLine = listOf(remoteId, updatedAt, "-").joinToString("\u001f"),
                localAckFingerprint = if (includeLocalAckFingerprints) fingerprintCategoryInbound(InventoryCategoryRow(
                    id=remoteId,ownerUserId="",name=cursor.getString(1),updatedAt=cursor.nullableString(2))) else null,
                acknowledgedRevision = cursor.getLong(4)
            )
        }
        ShopSyncRowDomain.PRODUCTS -> {
            requireCleanPhysicalRef(cursor, 13, 14)
            val remoteId = canonicalRecoveryEntityUuid(cursor.getString(0))
            val supplierId = cursor.nullableString(10)?.let(::canonicalRecoveryEntityUuid)
            val categoryId = cursor.nullableString(11)?.let(::canonicalRecoveryEntityUuid)
            val imageId = cursor.nullableString(8)?.let(::canonicalUuid)
            val imageUpdatedAt = cursor.nullableString(9)
            val updatedAt = canonicalTimestamp(cursor.nullableString(12))
            PhysicalRecoveryRow(
                remoteId = remoteId,
                payloadDigest = sha256(
                    recoveryProductPayloadFingerprint(
                        barcode = cursor.getString(1).trim(),
                        itemNumber = cursor.nullableString(2),
                        productName = cursor.nullableString(3),
                        secondProductName = cursor.nullableString(4),
                        purchasePrice = cursor.nullableDouble(5),
                        retailPrice = cursor.nullableDouble(6),
                        supplierRemoteId = supplierId,
                        categoryRemoteId = categoryId,
                        stockQuantity = cursor.nullableDouble(7),
                        primaryImageVersionId = imageId,
                        primaryImageUpdatedAt = imageUpdatedAt
                    )
                ),
                versionLine = listOf(
                    remoteId,
                    updatedAt,
                    "-",
                    categoryId ?: "-",
                    supplierId ?: "-",
                    imageId ?: "-",
                    canonicalTimestamp(imageUpdatedAt)
                ).joinToString("\u001f"),
                localAckFingerprint = if (includeLocalAckFingerprints) fingerprintProductInbound(InventoryProductRow(
                    id=remoteId,ownerUserId="",barcode=cursor.getString(1).trim(),
                    itemNumber=cursor.nullableString(2),productName=cursor.nullableString(3),
                    secondProductName=cursor.nullableString(4),purchasePrice=cursor.nullableDouble(5),
                    retailPrice=cursor.nullableDouble(6),stockQuantity=cursor.nullableDouble(7),
                    primaryImageVersionId=imageId,primaryImageUpdatedAt=imageUpdatedAt,
                    supplierId=supplierId,categoryId=categoryId,updatedAt=cursor.nullableString(12))) else null,
                acknowledgedRevision = cursor.getLong(14)
            )
        }
        ShopSyncRowDomain.PRICES -> {
            val remoteId = canonicalRecoveryEntityUuid(cursor.getString(0))
            PhysicalRecoveryRow(
                remoteId = remoteId,
                payloadDigest = sha256(
                    recoveryPricePayloadFingerprint(
                        id = remoteId,
                        productId = canonicalRecoveryEntityUuid(cursor.getString(1)),
                        type = cursor.getString(2),
                        price = cursor.getDouble(3),
                        effectiveAt = cursor.getString(4),
                        source = cursor.nullableString(5),
                        note = cursor.nullableString(6),
                        createdAt = cursor.getString(7)
                    )
                ),
                localAckFingerprint = if (includeLocalAckFingerprints) localAcknowledgedPriceFingerprint(InventoryProductPriceRow(
                    id=remoteId,ownerUserId="",productId=canonicalRecoveryEntityUuid(cursor.getString(1)),
                    type=cursor.getString(2),price=cursor.getDouble(3),effectiveAt=cursor.getString(4),
                    source=cursor.nullableString(5),note=cursor.nullableString(6),createdAt=cursor.getString(7))) else null,
                acknowledgedRevision = 0L
            )
        }
        ShopSyncRowDomain.HISTORY, ShopSyncRowDomain.IMAGES ->
            throw ShopSyncContractException("recovery_physical_domain_invalid")
    }

private fun requireCleanPhysicalRef(cursor: Cursor, localIndex: Int, syncedIndex: Int) {
    if (cursor.getInt(localIndex) != cursor.getInt(syncedIndex)) {
        throw ShopSyncContractException("recovery_physical_ref_dirty")
    }
}

internal suspend fun validateStagingDatabase(
    stagingDb: AppDatabase,
    generationId: String,
    checkpoint: ShopSyncRecoveryCheckpoint,
    activeStoreHistory: Boolean = false,
    localAcknowledgements: Boolean = false
) {
    val sql = stagingDb.openHelper.readableDatabase
    requirePragmaOk(sql, "PRAGMA integrity_check")
    sql.query("PRAGMA foreign_key_check").use { cursor ->
        if (cursor.moveToFirst()) {
            throw ShopSyncContractException("recovery_staging_foreign_key_violation")
        }
    }
    val materializedPriceCount = materializablePriceCount(stagingDb, generationId)
    val historyShadowCount = if (activeStoreHistory) {
        sql.query("SELECT COUNT(*) FROM history_entries WHERE deletedAt IS NOT NULL").use {
            check(it.moveToFirst())
            it.getLong(0)
        }
    } else 0L
    // A is immutable; a real same-generation ACK may already add a clean C body.
    // Count the same bounded canonical+ACK keyset used by the strict body proof below.
    suspend fun expectedCount(domain: ShopSyncRowDomain, canonicalCount: Long): Long {
        if (!localAcknowledgements) return canonicalCount
        var count = 0L
        var afterId: String? = null
        do {
            val rows = localAcknowledgedManifestPage(stagingDb, generationId, domain, afterId, PHYSICAL_VERIFY_PAGE_SIZE)
            count = Math.addExact(count, rows.size.toLong())
            afterId = rows.lastOrNull()?.remoteId
        } while (rows.size == PHYSICAL_VERIFY_PAGE_SIZE)
        return count
    }
    val suppliers = expectedCount(ShopSyncRowDomain.SUPPLIERS, checkpoint.catalog.suppliers.activeCount)
    val categories = expectedCount(ShopSyncRowDomain.CATEGORIES, checkpoint.catalog.categories.activeCount)
    val products = expectedCount(ShopSyncRowDomain.PRODUCTS, checkpoint.catalog.products.activeCount)
    val prices = expectedCount(ShopSyncRowDomain.PRICES, materializedPriceCount)
    val history = Math.addExact(expectedCount(ShopSyncRowDomain.HISTORY, checkpoint.history.activeCount), historyShadowCount)
    val expectedCounts = mapOf(
        "suppliers" to suppliers,
        "categories" to categories,
        "products" to products,
        "product_prices" to prices,
        "history_entries" to history,
        "supplier_remote_refs" to suppliers,
        "category_remote_refs" to categories,
        "product_remote_refs" to products,
        "product_price_remote_refs" to prices,
        "history_entry_remote_refs" to history
    )
    expectedCounts.forEach { (table, expected) ->
        if (queryCount(sql, table) != expected) {
            throw ShopSyncContractException("recovery_staging_table_count_mismatch_$table")
        }
    }
}


internal fun publishedBaselineFromMarker(
    checkpointB: ShopSyncRecoveryCheckpoint,
    markerC: ShopSyncConvergenceMarker
): ShopSyncRecoveryCheckpoint {
    if (!markerMatchesCheckpoint(markerC, checkpointB)) {
        throw ShopSyncContractException("recovery_convergence_marker_mismatch")
    }
    if (!SHA256_PATTERN.matches(markerC.checkpointDigest)) {
        throw ShopSyncContractException("recovery_marker_checkpoint_digest_invalid")
    }
    return checkpointB.copy(
        syncEvents = markerC.syncEvents,
        catalog = markerC.catalog,
        prices = markerC.prices,
        history = markerC.history,
        images = markerC.images,
        checkpointDigest = markerC.checkpointDigest
    )
}

internal fun validateRecoveryScopeIdentity(
    scope: ShopSyncScope,
    accountId: String,
    deviceId: String
) {
    if (
        scope.accountKey != sha256(accountId.trim().lowercase()) ||
        scope.deviceKey != sha256(deviceId.trim())
    ) {
        throw ShopSyncContractException("recovery_scope_identity_key_mismatch")
    }
}

private fun validateTailTargetedRows(
    result: ShopSyncTargetedRows,
    checkpoint: ShopSyncRecoveryCheckpoint,
    domain: ShopSyncRowDomain,
    shopId: String,
    requestedIds: List<String>
) {
    val expectedDomainFence = checkpoint.syncEvents.domainMaxIds[domain.syncEventDomain()]
        ?: throw ShopSyncContractException("recovery_tail_domain_fence_missing")
    val expectedScopeKind = if (domain == ShopSyncRowDomain.HISTORY) {
        checkpoint.scope.historyKind ?: checkpoint.scope.kind
    } else {
        checkpoint.scope.kind
    }
    val returned = result.rows.ids().map(::canonicalRecoveryEntityUuid)
    val expected = requestedIds.map(::canonicalRecoveryEntityUuid).toSet()
    if (
        result.schemaVersion != RECOVERY_TAIL_TARGETED_ROWS_SCHEMA ||
        result.shopId.lowercase() != shopId.lowercase() ||
        result.scope != checkpoint.scope ||
        result.domain != domain ||
        result.asOfEventMaxId != checkpoint.syncEvents.maxId ||
        parseShopSyncMaxEventId(result.currentScopeEventMaxId) <
            parseShopSyncMaxEventId(checkpoint.syncEvents.maxId) ||
        result.minimumDomainEventMaxId != expectedDomainFence ||
        parseShopSyncMaxEventId(result.materializedDomainEventMaxId) <
            parseShopSyncMaxEventId(expectedDomainFence) ||
        result.domainScope != expectedScopeKind ||
        result.requestedCount != requestedIds.size ||
        result.missingIds.isNotEmpty() ||
        returned.size != requestedIds.size ||
        returned.toSet() != expected
    ) {
        throw ShopSyncContractException("recovery_tail_targeted_contract_mismatch")
    }
    if (!tailRowsMatchScope(result.rows, expectedScopeKind, checkpoint.scope, shopId)) {
        throw ShopSyncContractException("recovery_tail_targeted_row_scope_mismatch")
    }
}

private fun tailRowsMatchScope(
    rows: ShopSyncRows,
    scopeKind: String,
    scope: ShopSyncScope,
    shopId: String
): Boolean {
    fun rowMatches(ownerUserId: String?, rowShopId: String?): Boolean = when (scopeKind) {
        ShopSyncScopeKinds.SHOP_SCOPED ->
            rowShopId?.lowercase() == shopId.lowercase()
        ShopSyncScopeKinds.LEGACY_OWNER_BRIDGE ->
            ownerUserId != null &&
                rowShopId == null &&
                scope.legacyOwnerKey == task126OwnerHash(ownerUserId.lowercase())
        ShopSyncScopeKinds.AUTHORIZED_SHOP_PLUS_LEGACY ->
            rowShopId?.lowercase() == shopId.lowercase() ||
                (
                    ownerUserId != null &&
                        rowShopId == null &&
                        scope.legacyOwnerKey == task126OwnerHash(ownerUserId.lowercase())
                    )
        else -> false
    }
    return when (rows) {
        is ShopSyncRows.Suppliers -> rows.values.all { rowMatches(it.ownerUserId, it.shopId) }
        is ShopSyncRows.Categories -> rows.values.all { rowMatches(it.ownerUserId, it.shopId) }
        is ShopSyncRows.Products -> rows.values.all { rowMatches(it.ownerUserId, it.shopId) }
        is ShopSyncRows.Prices -> rows.values.all { rowMatches(it.ownerUserId, it.shopId) }
        is ShopSyncRows.History -> rows.values.all { rowMatches(it.ownerUserId, it.shopId) }
        is ShopSyncRows.Images -> rows.values.all { rowMatches(it.ownerUserId, it.shopId) }
    }
}

internal fun validateTailPage(
    page: ShopSyncEventPage,
    checkpoint: ShopSyncRecoveryCheckpoint,
    shopId: String,
    cursorBeforePage: Long,
    pageLimit: Int = RECOVERY_TAIL_EVENT_PAGE_LIMIT
) {
    if (
        page.schemaVersion != RECOVERY_TAIL_EVENT_PAGE_SCHEMA ||
        page.shopId.lowercase() != shopId.lowercase() ||
        page.scope != checkpoint.scope ||
        page.asOfEventMaxId != checkpoint.syncEvents.maxId ||
        parseShopSyncMaxEventId(page.scopeEventMaxId) <
            parseShopSyncMaxEventId(checkpoint.syncEvents.maxId) ||
        page.asOfDomainEventMaxIds != checkpoint.syncEvents.domainMaxIds ||
        page.pageLimit != pageLimit ||
        page.rows.size > pageLimit ||
        (page.hasMore && page.nextAfterId == null) ||
        (!page.hasMore && page.nextAfterId != null)
    ) {
        throw ShopSyncContractException("recovery_tail_page_contract_mismatch")
    }
    if (page.rows.firstOrNull()?.id?.let { it <= cursorBeforePage } == true) {
        throw ShopSyncContractException("recovery_tail_event_order_invalid")
    }
}

internal fun validateTailEvent(
    event: SyncEventRemoteRow,
    checkpoint: ShopSyncRecoveryCheckpoint,
    shopId: String
) {
    val ids = event.entityIds ?: SyncEventEntityIds()
    val scopeKind = if (event.domain == SyncEventDomains.HISTORY) {
        checkpoint.scope.historyKind ?: checkpoint.scope.kind
    } else {
        checkpoint.scope.kind
    }
    val scopeMatches = when (scopeKind) {
        ShopSyncScopeKinds.SHOP_SCOPED ->
            event.shopId?.lowercase() == shopId.lowercase()
        ShopSyncScopeKinds.LEGACY_OWNER_BRIDGE ->
            event.shopId == null &&
                checkpoint.scope.legacyOwnerKey ==
                task126OwnerHash(event.ownerUserId.lowercase())
        ShopSyncScopeKinds.AUTHORIZED_SHOP_PLUS_LEGACY ->
            event.shopId?.lowercase() == shopId.lowercase() ||
                (
                    event.shopId == null &&
                        checkpoint.scope.legacyOwnerKey ==
                        task126OwnerHash(event.ownerUserId.lowercase())
                    )
        else -> false
    }
    if (
        !scopeMatches ||
        !event.timestampValid ||
        event.requiresFullRecovery ||
        !SyncEventContract.hasSupportedEventType(event.domain, event.eventType) ||
        !SyncEventContract.hasCompletePrimaryIds(event.domain, event.changedCount, ids)
    ) {
        throw ShopSyncContractException("recovery_tail_event_unsafe")
    }
}

internal const val SHOP_SYNC_ORDINARY_MAX_EVENTS = 100
private const val SHOP_SYNC_ORDINARY_MAX_ROWS = 2_000
private const val SHOP_SYNC_ORDINARY_MAX_CALLS = 64
private const val SHOP_SYNC_ORDINARY_MAX_BYTES = 16L * 1024L * 1024L

internal data class PreparedShopSyncOrdinaryWindow(
    val rows: Map<ShopSyncRowDomain, ShopSyncRows>,
    val removedImageProductIds: List<String>
)

/** Same canonical inputs as recovery, prepared completely outside the Room writer transaction. */
internal suspend fun prepareShopSyncOrdinaryWindow(
    db: AppDatabase,
    generationId: String,
    checkpoint: ShopSyncRecoveryCheckpoint,
    events: List<SyncEventRemoteRow>,
    read: suspend (ShopSyncRowDomain, List<String>) -> ShopSyncTargetedRows
): PreparedShopSyncOrdinaryWindow {
    val prepared = linkedMapOf<ShopSyncRowDomain, ShopSyncRows>()
    var calls = 0
    var bytes = 0L
    suspend fun fetch(domain: ShopSyncRowDomain, ids: Collection<String>) {
        val existing = prepared[domain]?.ids()?.toSet().orEmpty()
        val missing = ids.map(::canonicalRecoveryEntityUuid).distinct().filterNot { it in existing }.sorted()
        if (missing.size > SHOP_SYNC_ORDINARY_MAX_ROWS) {
            throw ShopSyncContractException("ordinary_prepared_window_bound_exceeded")
        }
        for (chunk in missing.chunked(DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.targetedRows(domain))) {
            coroutineContext.ensureActive()
            if (prepared.values.sumOf { it.size } + chunk.size > SHOP_SYNC_ORDINARY_MAX_ROWS ||
                ++calls > SHOP_SYNC_ORDINARY_MAX_CALLS) {
                throw ShopSyncContractException("ordinary_prepared_window_bound_exceeded")
            }
            val result = read(domain, chunk)
            if (result.rows.recoveryDomain() != domain) {
                throw ShopSyncContractException("ordinary_targeted_domain_mismatch")
            }
            if (result.missingIds.isNotEmpty()) {
                throw ShopSyncContractException("ordinary_targeted_missing_remote")
            }
            validateTailTargetedRows(result, checkpoint, domain, checkpoint.shopId, chunk)
            // The RPC is a lower-bound live read. Refuse material from a later
            // captured window before combining it with the server C receipt.
            if (result.currentScopeEventMaxId != checkpoint.syncEvents.maxId ||
                result.materializedDomainEventMaxId != checkpoint.syncEvents.domainMaxIds[domain.syncEventDomain()]) {
                throw ShopSyncContractException("ordinary_targeted_fence_changed")
            }
            if (result.responseBytes < 0L || result.largestRowBytes < 0L) {
                throw ShopSyncContractException("ordinary_prepared_window_bound_exceeded")
            }
            if (domain == ShopSyncRowDomain.HISTORY) {
                requireHistoryRowResponseWithinBudget(result.largestRowBytes)
            }
            bytes = Math.addExact(bytes, result.responseBytes)
            if (bytes > SHOP_SYNC_ORDINARY_MAX_BYTES) {
                throw ShopSyncContractException("ordinary_prepared_window_bound_exceeded")
            }
            result.rows.toManifestRows(generationId, domain)
            prepared[domain] = mergePreparedRecoveryRows(prepared[domain], result.rows)
            if (prepared.values.sumOf { it.size } > SHOP_SYNC_ORDINARY_MAX_ROWS) {
                throw ShopSyncContractException("ordinary_prepared_window_bound_exceeded")
            }
        }
    }
    val suppliers = events.flatMap { it.entityIds?.supplierIds.orEmpty() }.map(::canonicalRecoveryEntityUuid).toSet()
    val categories = events.flatMap { it.entityIds?.categoryIds.orEmpty() }.map(::canonicalRecoveryEntityUuid).toSet()
    val productIds = events.flatMap { it.entityIds?.productIds.orEmpty() }.map(::canonicalRecoveryEntityUuid).toMutableSet()
    fetch(ShopSyncRowDomain.SUPPLIERS, suppliers)
    fetch(ShopSyncRowDomain.CATEGORIES, categories)
    fetch(ShopSyncRowDomain.PRICES, events.flatMap { it.entityIds?.priceIds.orEmpty() })
    fetch(ShopSyncRowDomain.HISTORY, events.flatMap { it.entityIds?.sessionIds.orEmpty() })
    (prepared[ShopSyncRowDomain.PRICES] as? ShopSyncRows.Prices)?.values?.forEach {
        productIds += canonicalRecoveryEntityUuid(it.productId)
    }
    // Supplier/category cascades may change product material although those
    // product IDs are not primary IDs of the triggering catalog event.
    if (suppliers.isNotEmpty() || categories.isNotEmpty()) {
        var cursor: String? = null
        do {
            coroutineContext.ensureActive()
            val page = db.syncRecoveryManifestDao().page(generationId, ShopSyncRowDomain.PRODUCTS.wireValue,
                cursor, MANIFEST_VERIFY_PAGE_SIZE)
            page.filter { it.active }.forEach { row ->
                val fields = productManifestParts(row)
                if (fields[3] in categories || fields[4] in suppliers) productIds += row.remoteId
            }
            if (productIds.size > SHOP_SYNC_ORDINARY_MAX_ROWS) {
                throw ShopSyncContractException("ordinary_prepared_window_bound_exceeded")
            }
            cursor = page.lastOrNull()?.remoteId
        } while (page.size == MANIFEST_VERIFY_PAGE_SIZE)
    }
    fetch(ShopSyncRowDomain.PRODUCTS, productIds)
    val products = (prepared[ShopSyncRowDomain.PRODUCTS] as? ShopSyncRows.Products)?.values.orEmpty()
    fetch(ShopSyncRowDomain.SUPPLIERS, products.filter { it.deletedAt == null }.mapNotNull { it.supplierId })
    fetch(ShopSyncRowDomain.CATEGORIES, products.filter { it.deletedAt == null }.mapNotNull { it.categoryId })
    val removedImages = products.filter { it.deletedAt == null && it.primaryImageVersionId == null }
        .map { canonicalRecoveryEntityUuid(it.id) }
    val imageIds = products.filter { it.deletedAt == null && it.primaryImageVersionId != null }
        .map { canonicalRecoveryEntityUuid(it.id) }.toMutableSet()
    val restoredParents = hashSetOf<String>()
    for (batch in products.chunked(PHYSICAL_VERIFY_PAGE_SIZE)) {
        val ids = batch.map { canonicalRecoveryEntityUuid(it.id) }
        val previous = db.syncRecoveryManifestDao().getProductsByRemoteIds(generationId, ids).associateBy { it.remoteId }
        val imagePrevious = db.syncRecoveryManifestDao().getByRemoteIds(
            generationId, ShopSyncRowDomain.IMAGES.wireValue, ids
        ).associateBy { it.remoteId }
        batch.forEach { product ->
            val id = canonicalRecoveryEntityUuid(product.id)
            if (product.deletedAt != null && imagePrevious[id] != null) imageIds += id
            if (product.deletedAt == null && previous[id]?.active == false) restoredParents += id
        }
    }
    fetch(ShopSyncRowDomain.IMAGES, imageIds)
    val byProduct = products.associateBy { canonicalRecoveryEntityUuid(it.id) }
    (prepared[ShopSyncRowDomain.IMAGES] as? ShopSyncRows.Images)?.values.orEmpty().forEach { image ->
        val product = byProduct[canonicalRecoveryEntityUuid(image.productId)]
            ?: throw ShopSyncContractException("ordinary_image_product_missing")
        if (if (product.deletedAt != null) image.productDeletedAt != product.deletedAt
            else image.productDeletedAt != null || product.primaryImageVersionId?.lowercase() != image.versionId.lowercase()) {
            throw ShopSyncContractException("ordinary_image_product_mismatch")
        }
    }
    // A restored parent needs the original retained price bodies. Hashes in
    // the manifest alone cannot recreate them or prove physical application.
    if (restoredParents.isNotEmpty()) {
        var cursor: String? = null
        val retainedIds = linkedSetOf<String>()
        do {
            coroutineContext.ensureActive()
            val page = db.syncRecoveryManifestDao().page(generationId, ShopSyncRowDomain.PRICES.wireValue,
                cursor, MANIFEST_VERIFY_PAGE_SIZE)
            page.forEach { row -> if (priceManifestParentId(row) in restoredParents) retainedIds += row.remoteId }
            if (retainedIds.size > SHOP_SYNC_ORDINARY_MAX_ROWS) {
                throw ShopSyncContractException("ordinary_prepared_window_bound_exceeded")
            }
            cursor = page.lastOrNull()?.remoteId
        } while (page.size == MANIFEST_VERIFY_PAGE_SIZE)
        fetch(ShopSyncRowDomain.PRICES, retainedIds)
    }
    return PreparedShopSyncOrdinaryWindow(prepared, removedImages)
}

private fun mergePreparedRecoveryRows(old: ShopSyncRows?, next: ShopSyncRows): ShopSyncRows {
    fun <T> merge(previous: List<T>, incoming: List<T>, id: (T) -> String): List<T> {
        val rows = previous.associateByTo(linkedMapOf(), id)
        incoming.forEach { value ->
            val key = id(value)
            if (rows[key]?.let { it != value } == true) {
                throw ShopSyncContractException("ordinary_targeted_material_changed")
            }
            rows[key] = value
        }
        return rows.toSortedMap().values.toList()
    }
    return when (next) {
        is ShopSyncRows.Suppliers -> ShopSyncRows.Suppliers(merge((old as? ShopSyncRows.Suppliers)?.values.orEmpty(), next.values) { it.id })
        is ShopSyncRows.Categories -> ShopSyncRows.Categories(merge((old as? ShopSyncRows.Categories)?.values.orEmpty(), next.values) { it.id })
        is ShopSyncRows.Products -> ShopSyncRows.Products(merge((old as? ShopSyncRows.Products)?.values.orEmpty(), next.values) { it.id })
        is ShopSyncRows.Prices -> ShopSyncRows.Prices(merge((old as? ShopSyncRows.Prices)?.values.orEmpty(), next.values) { it.id })
        is ShopSyncRows.History -> ShopSyncRows.History(merge((old as? ShopSyncRows.History)?.values.orEmpty(), next.values) { it.remoteId })
        is ShopSyncRows.Images -> ShopSyncRows.Images(merge((old as? ShopSyncRows.Images)?.values.orEmpty(), next.values) { it.productId })
    }
}

private class RecoveryTransferBudget(
    private val limits: ShopSyncRecoveryResourceLimits
) {
    private val domainBytes = mutableMapOf<ShopSyncRowDomain, Long>()
    private val domainRows = mutableMapOf<ShopSyncRowDomain, Long>()
    private var totalBytes = 0L
    private var totalRows = 0L
    private var tailTargetedCalls = 0

    fun restore(domain: ShopSyncRowDomain, accepted: RecoveryPageProgress.Accepted) {
        if (accepted.pages > limits.rows(domain) / limits.pageRows(domain).toLong() + 2L ||
            accepted.bytes > limits.domainResponseBytes || accepted.rows > limits.rows(domain) ||
            (domain == ShopSyncRowDomain.HISTORY && accepted.largestRowBytes > limits.historyRowResponseBytes)) {
            throw ShopSyncContractException("recovery_resume_resource_exceeded")
        }
        val nextBytes = checkedRecoveryAdd(totalBytes, accepted.bytes, "recovery_resume_budget_overflow")
        val nextRows = checkedRecoveryAdd(totalRows, accepted.rows, "recovery_resume_budget_overflow")
        if (nextBytes > limits.totalResponseBytes || nextRows > limits.totalRows) {
            throw ShopSyncContractException("recovery_resume_resource_exceeded")
        }
        domainBytes[domain] = accepted.bytes
        domainRows[domain] = accepted.rows
        totalBytes = nextBytes
        totalRows = nextRows
    }

    fun record(domain: ShopSyncRowDomain, pageBytes: Long, largestRowBytes: Long) {
        if (largestRowBytes < 0L) {
            throw ShopSyncContractException("recovery_row_response_size_invalid")
        }
        if (pageBytes <= 0L || pageBytes > limits.pageResponseBytes(domain)) {
            throw ShopSyncContractException("recovery_page_response_budget_exceeded")
        }
        if (domain == ShopSyncRowDomain.HISTORY) {
            requireHistoryRowResponseWithinBudget(largestRowBytes, limits)
        }
        val nextDomain = checkedRecoveryAdd(
            domainBytes[domain] ?: 0L,
            pageBytes,
            "recovery_domain_response_budget_overflow"
        )
        if (nextDomain > limits.domainResponseBytes) {
            throw ShopSyncContractException("recovery_domain_response_budget_exceeded")
        }
        val nextTotal = checkedRecoveryAdd(
            totalBytes,
            pageBytes,
            "recovery_total_response_budget_overflow"
        )
        if (nextTotal > limits.totalResponseBytes) {
            throw ShopSyncContractException("recovery_total_response_budget_exceeded")
        }
        domainBytes[domain] = nextDomain
        totalBytes = nextTotal
    }

    fun recordRows(domain: ShopSyncRowDomain, rows: Int) {
        if (rows < 0) throw ShopSyncContractException("recovery_live_row_count_invalid")
        val nextDomain = checkedRecoveryAdd(
            domainRows[domain] ?: 0L,
            rows.toLong(),
            "recovery_live_row_budget_overflow"
        )
        if (nextDomain > limits.rows(domain)) {
            throw ShopSyncContractException("recovery_live_row_budget_exceeded_${domain.wireValue}")
        }
        val nextTotal = checkedRecoveryAdd(
            totalRows,
            rows.toLong(),
            "recovery_live_total_row_budget_overflow"
        )
        if (nextTotal > limits.totalRows) {
            throw ShopSyncContractException("recovery_live_total_row_budget_exceeded")
        }
        domainRows[domain] = nextDomain
        totalRows = nextTotal
    }

    fun recordTailEventPage(responseBytes: Long) {
        if (responseBytes <= 0L || responseBytes > limits.defaultPageResponseBytes) {
            throw ShopSyncContractException("recovery_tail_event_response_budget_exceeded")
        }
        totalBytes = checkedRecoveryAdd(
            totalBytes,
            responseBytes,
            "recovery_tail_total_response_budget_overflow"
        )
        if (totalBytes > limits.totalResponseBytes) {
            throw ShopSyncContractException("recovery_tail_total_response_budget_exceeded")
        }
    }

    fun recordTailTargetedResponse(
        domain: ShopSyncRowDomain,
        responseBytes: Long,
        largestRowBytes: Long
    ) {
        tailTargetedCalls += 1
        if (tailTargetedCalls > RECOVERY_TAIL_MAX_TARGETED_CALLS) {
            throw ShopSyncContractException("recovery_tail_targeted_call_bound_exceeded")
        }
        record(domain, responseBytes, largestRowBytes)
    }
}

private class RecoveryManifestReader(
    private val dao: SyncRecoveryManifestDao,
    private val generationId: String,
    private val domain: ShopSyncRowDomain
) {
    private var afterId: String? = null
    private var page = emptyList<SyncRecoveryManifestRow>()
    private var index = 0
    private var exhausted = false

    suspend fun next(): SyncRecoveryManifestRow? {
        if (index < page.size) return page[index++]
        if (exhausted) return null
        page = dao.page(
            generationId = generationId,
            domain = domain.wireValue,
            afterId = afterId,
            limit = RELATION_VERIFY_PAGE_SIZE
        )
        index = 0
        if (page.isEmpty()) {
            exhausted = true
            return null
        }
        afterId = page.last().remoteId
        if (page.size < RELATION_VERIFY_PAGE_SIZE) exhausted = true
        return page[index++]
    }
}

private fun productManifestParts(row: SyncRecoveryManifestRow): List<String> {
    val parts = row.versionLine.split('\u001f', limit = 7)
    if (parts.size != 7 || parts[0] != row.remoteId) {
        throw ShopSyncContractException("recovery_product_manifest_invalid")
    }
    return parts
}

private fun productPrimaryImageVersion(row: SyncRecoveryManifestRow): String? =
    productManifestParts(row)[5].takeUnless { it == "-" }?.let(::canonicalUuid)

private fun productDeletedAt(row: SyncRecoveryManifestRow): String? =
    productManifestParts(row)[2].takeUnless { it == "-" }?.let(::canonicalTimestamp)

private fun imageProductDeletedAt(row: SyncRecoveryManifestRow): String? {
    return imageManifestParts(row)[3].takeUnless { it == "-" }?.let(::canonicalTimestamp)
}

private fun imageVersionId(row: SyncRecoveryManifestRow): String =
    imageManifestParts(row)[1].let(::canonicalUuid)

private fun imageManifestParts(row: SyncRecoveryManifestRow): List<String> {
    val parts = row.versionLine.split('\u001f', limit = 15)
    if (parts.size != 15 || parts[0] != row.remoteId) {
        throw ShopSyncContractException("recovery_image_manifest_invalid")
    }
    return parts
}

private fun historyManifestPayloadVersion(row: SyncRecoveryManifestRow): Int {
    val parts = row.versionLine.split('\u001f', limit = 5)
    if (parts.size != 5 || parts[0] != row.remoteId) {
        throw ShopSyncContractException("recovery_history_manifest_invalid")
    }
    return parts[3].toIntOrNull()
        ?.takeIf {
            it == SESSION_PAYLOAD_VERSION_LEGACY_V1 || it == SESSION_PAYLOAD_VERSION
        }
        ?: throw ShopSyncContractException("recovery_history_manifest_payload_version_invalid")
}

/**
 * V1 did not materialize display/overlay fields, so its bridge receipt remains
 * intentionally compatibility-only. V2 does materialize those fields: prove
 * the actual Room entry reconstructs the same source payload fingerprint
 * before a generation can become visible.
 */
private fun validateHistoryPhysicalMaterialization(
    manifest: SyncRecoveryManifestRow,
    physical: PhysicalRecoveryRow
) {
    if (historyManifestPayloadVersion(manifest) != SESSION_PAYLOAD_VERSION) return
    val state = physical.history
        ?: throw ShopSyncContractException("recovery_physical_history_state_missing")
    val entry = state.entry
    val materializedFingerprint = historyPhysicalPayloadFingerprint(entry, physical.remoteId)
    if (materializedFingerprint != state.sourcePayloadFingerprint) {
        throw ShopSyncContractException("recovery_physical_history_v2_payload_mismatch")
    }
}

private fun historyPhysicalPayloadFingerprint(entry: HistoryEntry, remoteId: String): String =
    SessionRemotePayload(
        remoteId = remoteId,
        payloadVersion = SESSION_PAYLOAD_VERSION,
        displayName = entry.displayName,
        timestamp = entry.timestamp,
        supplier = entry.supplier,
        category = entry.category,
        isManualEntry = entry.isManualEntry,
        data = entry.data,
        sessionOverlay = SessionOverlay(
            overlaySchema = SESSION_OVERLAY_SCHEMA,
            editable = entry.editable,
            complete = entry.complete
        ),
        deletedAt = entry.deletedAt
    ).payloadFingerprint()

private fun checkedRecoveryAdd(left: Long, right: Long, code: String): Long {
    if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) {
        throw ShopSyncContractException(code)
    }
    return left + right
}

/**
 * Spazio addizionale conservativo necessario durante il commit Room: lo
 * staging resta presente, mentre rollback journal/WAL e crescita del DB attivo
 * possono richiedere fino alla dimensione fisica sia di G-old sia di G-new.
 */
internal fun requiredRecoveryActivationHeadroomBytes(
    stagingGenerationBytes: Long,
    activeGenerationBytes: Long,
    fixedHeadroomBytes: Long
): Long {
    if (
        stagingGenerationBytes < 0L ||
        activeGenerationBytes < 0L ||
        fixedHeadroomBytes < 0L
    ) {
        throw ShopSyncContractException("recovery_activation_headroom_size_invalid")
    }
    val generations = checkedRecoveryAdd(
        stagingGenerationBytes,
        activeGenerationBytes,
        "recovery_activation_headroom_overflow"
    )
    return checkedRecoveryAdd(
        generations,
        fixedHeadroomBytes,
        "recovery_activation_headroom_overflow"
    )
}

private fun recoveryGenerationSizeBytes(databaseFile: File): Long =
    listOf(
        databaseFile,
        File(databaseFile.path + "-journal"),
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm")
    ).fold(0L) { total, file ->
        checkedRecoveryAdd(
            total,
            file.takeIf(File::exists)?.length() ?: 0L,
            "recovery_generation_disk_size_overflow"
        )
    }

private data class HistoryPhysicalRecoveryState(
    val entry: HistoryEntry,
    val sourcePayloadFingerprint: String
)

private data class PhysicalRecoveryRow(
    val remoteId: String,
    val payloadDigest: String,
    val versionLine: String? = null,
    val history: HistoryPhysicalRecoveryState? = null,
    val localAckFingerprint: String? = null,
    val acknowledgedRevision: Long? = null
)

private data class RecoveryTableSpec(val table: String, val columns: List<String>)

private val BUSINESS_DELETE_ORDER = listOf(
    "product_price_remote_refs",
    "product_prices",
    "product_remote_refs",
    "products",
    "supplier_remote_refs",
    "category_remote_refs",
    "suppliers",
    "categories",
    "history_entry_remote_refs",
    "history_entries"
)

private val BUSINESS_COPY_ORDER = listOf(
    RecoveryTableSpec("suppliers", listOf("id", "name")),
    RecoveryTableSpec("categories", listOf("id", "name")),
    RecoveryTableSpec(
        "products",
        listOf(
            "id", "barcode", "itemNumber", "productName", "secondProductName",
            "purchasePrice", "retailPrice", "oldPurchasePrice", "oldRetailPrice",
            "supplierId", "categoryId", "stockQuantity", "primaryImageVersionId",
            "primaryImageUpdatedAt"
        )
    ),
    RecoveryTableSpec(
        "product_prices",
        listOf("id", "productId", "type", "price", "effectiveAt", "source", "note", "createdAt")
    ),
    RecoveryTableSpec(
        "supplier_remote_refs",
        listOf(
            "id", "supplierId", "remoteId", "localChangeRevision", "lastSyncedLocalRevision",
            "lastRemoteAppliedAt", "lastRemotePayloadFingerprint", "remoteUpdatedAt"
        )
    ),
    RecoveryTableSpec(
        "category_remote_refs",
        listOf(
            "id", "categoryId", "remoteId", "localChangeRevision", "lastSyncedLocalRevision",
            "lastRemoteAppliedAt", "lastRemotePayloadFingerprint", "remoteUpdatedAt"
        )
    ),
    RecoveryTableSpec(
        "product_remote_refs",
        listOf(
            "id", "productId", "remoteId", "localChangeRevision", "lastSyncedLocalRevision",
            "lastRemoteAppliedAt", "lastRemotePayloadFingerprint", "remoteUpdatedAt",
            "localChangedFields"
        )
    ),
    RecoveryTableSpec("product_price_remote_refs", listOf("id", "productPriceId", "remoteId")),
    RecoveryTableSpec(
        "history_entries",
        listOf(
            "uid", "id", "displayName", "timestamp", "data", "editable", "complete",
            "supplier", "category", "wasExported", "syncStatus", "orderTotal", "paymentTotal",
            "missingItems", "totalItems", "isManualEntry", "deletedAt"
        )
    ),
    RecoveryTableSpec(
        "history_entry_remote_refs",
        listOf(
            "id", "historyEntryUid", "remoteId", "localChangeRevision", "lastSyncedLocalRevision",
            "lastRemoteAppliedAt", "lastRemotePayloadFingerprint"
        )
    )
)

private val BASELINE_TABLE = RecoveryTableSpec(
    "sync_recovery_baseline",
    listOf(
        "id", "generationId", "ownerHash", "storeScope", "shopId", "deviceId",
        "scopeKind", "scopeKey", "checkpointJson", "activatedAtMs"
    )
)

private val MANIFEST_TABLE = RecoveryTableSpec(
    "sync_recovery_manifest",
    listOf(
        "generationId", "domain", "remoteId", "active", "idLine", "versionLine",
        "identityLine", "payloadDigest"
    )
)

private fun copyTable(
    source: SupportSQLiteDatabase,
    destination: SupportSQLiteDatabase,
    spec: RecoveryTableSpec
) {
    val columns = spec.columns.joinToString(",") { "`$it`" }
    val placeholders = spec.columns.joinToString(",") { "?" }
    source.query("SELECT $columns FROM `${spec.table}`").use { cursor ->
        while (cursor.moveToNext()) {
            destination.execSQL(
                "INSERT INTO `${spec.table}` ($columns) VALUES ($placeholders)",
                Array(spec.columns.size) { index -> cursor.valueAt(index) }
            )
        }
    }
}

/** Validated staging rows become transaction-local sources for the existing overlay joins. */
private fun copyRecoverySourceToTemp(
    source: SupportSQLiteDatabase,
    destination: SupportSQLiteDatabase
): List<String> = (BUSINESS_COPY_ORDER + BASELINE_TABLE + MANIFEST_TABLE).map { spec ->
    val target = "mc_recovery_source_${spec.table}"
    val columns = spec.columns.joinToString(",") { "`$it`" }
    val placeholders = spec.columns.joinToString(",") { "?" }
    destination.execSQL("CREATE TEMP TABLE `$target` AS SELECT $columns FROM `${spec.table}` WHERE 0")
    val expected = queryCount(source, spec.table)
    var copied = 0L
    destination.compileStatement("INSERT INTO `$target` ($columns) VALUES ($placeholders)").use { insert ->
        source.query("SELECT $columns FROM `${spec.table}`").use { cursor ->
            while (cursor.moveToNext()) {
                insert.clearBindings()
                spec.columns.indices.forEach { index ->
                    when (val value = cursor.valueAt(index)) {
                        null -> insert.bindNull(index + 1)
                        is Long -> insert.bindLong(index + 1, value)
                        is Double -> insert.bindDouble(index + 1, value)
                        is String -> insert.bindString(index + 1, value)
                        is ByteArray -> insert.bindBlob(index + 1, value)
                        else -> throw ShopSyncContractException("recovery_cursor_type_unsupported")
                    }
                }
                insert.executeInsert()
                copied++
            }
        }
    }
    if (copied != expected || queryCount(destination, target) != expected) {
        throw ShopSyncContractException("recovery_overlay_source_count_mismatch")
    }
    val uniqueColumns = when (spec.table) {
        "history_entries" -> listOf("uid")
        "supplier_remote_refs" -> listOf("id", "supplierId", "remoteId")
        "category_remote_refs" -> listOf("id", "categoryId", "remoteId")
        "product_remote_refs" -> listOf("id", "productId", "remoteId")
        "product_price_remote_refs" -> listOf("id", "productPriceId", "remoteId")
        "history_entry_remote_refs" -> listOf("id", "historyEntryUid", "remoteId")
        "suppliers", "categories", "products", "product_prices" -> listOf("id")
        else -> emptyList()
    }
    uniqueColumns.forEach { key ->
        destination.execSQL("CREATE UNIQUE INDEX `${target}_$key` ON `$target` (`$key`)")
    }
    target
}

private fun Cursor.valueAt(index: Int): Any? = when (getType(index)) {
    Cursor.FIELD_TYPE_NULL -> null
    Cursor.FIELD_TYPE_INTEGER -> getLong(index)
    Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
    Cursor.FIELD_TYPE_STRING -> getString(index)
    Cursor.FIELD_TYPE_BLOB -> getBlob(index)
    else -> throw ShopSyncContractException("recovery_cursor_type_unsupported")
}

private fun Cursor.nullableString(index: Int): String? =
    if (isNull(index)) null else getString(index)

private fun Cursor.nullableDouble(index: Int): Double? =
    if (isNull(index)) null else getDouble(index)

private fun queryCount(db: SupportSQLiteDatabase, table: String): Long =
    db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
        if (!cursor.moveToFirst()) throw ShopSyncContractException("recovery_count_missing")
        cursor.getLong(0)
    }

private fun requirePragmaOk(db: SupportSQLiteDatabase, pragma: String) {
    db.query(pragma).use { cursor ->
        if (!cursor.moveToFirst() || cursor.getString(0) != "ok" || cursor.moveToNext()) {
            throw ShopSyncContractException("recovery_integrity_check_failed")
        }
    }
}

internal fun ShopSyncRows.toManifestRows(
    generationId: String,
    domain: ShopSyncRowDomain
): List<SyncRecoveryManifestRow> {
    val separator = "\u001f"
    fun row(
        remoteId: String,
        active: Boolean,
        idLine: String = remoteId,
        versionLine: String,
        identityLine: String? = null,
        payloadDigest: String? = null
    ) = SyncRecoveryManifestRow(
        generationId = generationId,
        domain = domain.wireValue,
        remoteId = canonicalRecoveryEntityUuid(remoteId),
        active = active,
        idLine = idLine.lowercase(),
        versionLine = versionLine,
        identityLine = identityLine,
        payloadDigest = payloadDigest
    )

    return when (this) {
        is ShopSyncRows.Suppliers -> values.map { value ->
            val id = canonicalRecoveryEntityUuid(value.id)
            val active = value.deletedAt == null
            row(
                remoteId = id,
                active = active,
                versionLine = listOf(id, canonicalTimestamp(value.updatedAt), canonicalTimestamp(value.deletedAt))
                    .joinToString(separator),
                payloadDigest = sha256(recoverySupplierPayloadFingerprint(value.name)).takeIf { active }
            )
        }
        is ShopSyncRows.Categories -> values.map { value ->
            val id = canonicalRecoveryEntityUuid(value.id)
            val active = value.deletedAt == null
            row(
                remoteId = id,
                active = active,
                versionLine = listOf(id, canonicalTimestamp(value.updatedAt), canonicalTimestamp(value.deletedAt))
                    .joinToString(separator),
                payloadDigest = sha256(recoveryCategoryPayloadFingerprint(value.name)).takeIf { active }
            )
        }
        is ShopSyncRows.Products -> values.map { value ->
            val id = canonicalRecoveryEntityUuid(value.id)
            val active = value.deletedAt == null
            if (
                !active && (
                    value.categoryId != null ||
                        value.supplierId != null ||
                        value.primaryImageVersionId != null ||
                        value.primaryImageUpdatedAt != null
                    )
            ) {
                throw ShopSyncContractException("recovery_product_tombstone_reference_invalid")
            }
            val categoryId = value.categoryId?.let(::canonicalRecoveryEntityUuid)
            val supplierId = value.supplierId?.let(::canonicalRecoveryEntityUuid)
            val imageVersionId = value.primaryImageVersionId?.let(::canonicalUuid)
            row(
                remoteId = id,
                active = active,
                versionLine = listOf(
                    id,
                    canonicalTimestamp(value.updatedAt),
                    canonicalTimestamp(value.deletedAt),
                    categoryId.takeIf { active } ?: "-",
                    supplierId.takeIf { active } ?: "-",
                    imageVersionId.takeIf { active } ?: "-",
                    value.primaryImageUpdatedAt.takeIf { active }
                        ?.let(::canonicalTimestamp) ?: "-"
                ).joinToString(separator),
                identityLine = listOf(
                    id,
                    sha256(value.barcode),
                    sha256(value.itemNumber.orEmpty())
                ).joinToString(separator),
                payloadDigest = sha256(recoveryProductPayloadFingerprint(value)).takeIf { active }
            )
        }
        is ShopSyncRows.Prices -> values.map { value ->
            val id = canonicalRecoveryEntityUuid(value.id)
            val productId = canonicalRecoveryEntityUuid(value.productId)
            validateRecoveryPriceType(value.type)
            row(
                remoteId = id,
                active = true,
                versionLine = listOf(
                    id,
                    canonicalTimestamp(value.updatedAt),
                    productId,
                    canonicalRecoveryPrice(value),
                    value.type,
                    canonicalLegacyTimestampForDigest(value.effectiveAt),
                    canonicalLegacyTimestampForDigest(value.createdAt),
                    sha256(value.source.orEmpty()),
                    sha256(value.note.orEmpty())
                ).joinToString(separator),
                payloadDigest = sha256(recoveryPricePayloadFingerprint(value))
            )
        }
        is ShopSyncRows.History -> values.map { value ->
            val id = canonicalRecoveryEntityUuid(value.remoteId)
            val deleted = value.deletedAt != null
            val versionParts = buildList {
                add(id)
                add(canonicalTimestamp(value.updatedAt))
                add(canonicalTimestamp(value.deletedAt))
                add(value.payloadVersion.toString())
                if (deleted) {
                    add("-")
                } else {
                    add(canonicalHistoryTimestampForDigest(value.timestamp))
                    add(sha256(value.supplier))
                    add(sha256(value.category))
                    add(value.isManualEntry.toString())
                    add(sha256(value.displayName.orEmpty()))
                    add(requiredRecoveryDigest(value.dataCheckpointDigest, "recovery_history_data_digest"))
                    add(requiredRecoveryDigest(value.overlayCheckpointDigest, "recovery_history_overlay_digest"))
                }
            }
            row(
                remoteId = id,
                active = !deleted,
                versionLine = versionParts.joinToString(separator),
                payloadDigest = recoveryHistoryPayloadDigest(value)
                    .takeIf { !deleted }
            )
        }
        is ShopSyncRows.Images -> values.map { value ->
            val productId = canonicalRecoveryEntityUuid(value.productId)
            val versionId = canonicalUuid(value.versionId)
            validateImageVariant(value.main, ProductImageVariant.MAIN)
            validateImageVariant(value.thumb, ProductImageVariant.THUMB)
            if (value.status != "ready") throw ShopSyncContractException("recovery_image_status_invalid")
            val versionLine = listOf(
                productId,
                versionId,
                value.status,
                canonicalTimestamp(value.productDeletedAt),
                canonicalTimestamp(value.finalizedAt),
                value.main.sha256.lowercase(),
                value.main.bytes.toString(),
                value.main.width.toString(),
                value.main.height.toString(),
                value.main.mime,
                value.thumb.sha256.lowercase(),
                value.thumb.bytes.toString(),
                value.thumb.width.toString(),
                value.thumb.height.toString(),
                value.thumb.mime
            ).joinToString(separator)
            row(
                remoteId = productId,
                active = value.productDeletedAt == null,
                versionLine = versionLine,
                payloadDigest = sha256(versionLine).takeIf { value.productDeletedAt == null }
            )
        }
    }
}

private fun validateImageVariant(
    value: ShopSyncImageVariantRow,
    variant: ProductImageVariant
) {
    val maximumBytes = when (variant) {
        ProductImageVariant.MAIN -> PRODUCT_IMAGE_MAIN_MAX_BYTES
        ProductImageVariant.THUMB -> PRODUCT_IMAGE_THUMB_MAX_BYTES
    }
    val maximumSide = when (variant) {
        ProductImageVariant.MAIN -> PRODUCT_IMAGE_MAIN_MAX_SIDE
        ProductImageVariant.THUMB -> PRODUCT_IMAGE_THUMB_MAX_SIDE
    }
    if (
        !SHA256_PATTERN.matches(value.sha256) ||
        value.bytes !in 1L..maximumBytes.toLong() ||
        value.width <= 0 ||
        value.height <= 0 ||
        maxOf(value.width, value.height) > maximumSide ||
        value.mime != "image/jpeg"
    ) {
        throw ShopSyncContractException("recovery_image_metadata_invalid")
    }
}

private class CanonicalLineDigest {
    private var state = sha256("")

    fun add(line: String) {
        val byteLength = line.toByteArray(Charsets.UTF_8).size
        state = sha256("$state\u001f$byteLength:$line")
    }

    fun finish(): String = state
}

/** Internal seam for the shared Admin/iOS/Android V6 golden-vector test. */
internal fun shopSyncCheckpointChainDigest(lines: Iterable<String>): String {
    val digest = CanonicalLineDigest()
    lines.forEach(digest::add)
    return digest.finish()
}

private fun ShopSyncRecoveryCheckpoint.domain(domain: ShopSyncRowDomain): ShopSyncDomainCheckpoint =
    when (domain) {
        ShopSyncRowDomain.SUPPLIERS -> catalog.suppliers
        ShopSyncRowDomain.CATEGORIES -> catalog.categories
        ShopSyncRowDomain.PRODUCTS -> catalog.products
        ShopSyncRowDomain.PRICES -> prices
        ShopSyncRowDomain.HISTORY -> history
        ShopSyncRowDomain.IMAGES -> images
    }

internal fun ShopSyncRowDomain.syncEventDomain(): String = when (this) {
    ShopSyncRowDomain.SUPPLIERS,
    ShopSyncRowDomain.CATEGORIES,
    ShopSyncRowDomain.PRODUCTS,
    ShopSyncRowDomain.IMAGES -> SyncEventDomains.CATALOG
    ShopSyncRowDomain.PRICES -> SyncEventDomains.PRICES
    ShopSyncRowDomain.HISTORY -> SyncEventDomains.HISTORY
}

private fun ShopSyncRows.recoveryDomain(): ShopSyncRowDomain = when (this) {
    is ShopSyncRows.Suppliers -> ShopSyncRowDomain.SUPPLIERS
    is ShopSyncRows.Categories -> ShopSyncRowDomain.CATEGORIES
    is ShopSyncRows.Products -> ShopSyncRowDomain.PRODUCTS
    is ShopSyncRows.Prices -> ShopSyncRowDomain.PRICES
    is ShopSyncRows.History -> ShopSyncRowDomain.HISTORY
    is ShopSyncRows.Images -> ShopSyncRowDomain.IMAGES
}

/**
 * A checkpoint B is intentionally called with A as verified baseline. Its
 * diagnostic baseline fields and digest therefore differ from A even when the
 * materialized snapshot is unchanged. Compare only authoritative material.
 */
private fun sameRecoveryMaterial(
    first: ShopSyncRecoveryCheckpoint,
    second: ShopSyncRecoveryCheckpoint
): Boolean =
    first.status == second.status &&
        first.shopId == second.shopId &&
        first.scope == second.scope &&
        first.syncEvents.maxId == second.syncEvents.maxId &&
        first.syncEvents.domainMaxIds == second.syncEvents.domainMaxIds &&
        first.catalog == second.catalog &&
        first.prices == second.prices &&
        first.history == second.history &&
        first.images == second.images &&
        first.integrity == second.integrity

private fun markerMatchesCheckpoint(
    marker: ShopSyncConvergenceMarker,
    checkpoint: ShopSyncRecoveryCheckpoint
): Boolean =
    marker.schemaVersion == "shop-sync-convergence-marker-v1" &&
        marker.status == "ready" &&
        marker.serverNoWorkEligible &&
        marker.shopId == checkpoint.shopId &&
        marker.scope == checkpoint.scope &&
        marker.syncEvents.maxId == checkpoint.syncEvents.maxId &&
        marker.syncEvents.verifiedBaselineId == checkpoint.syncEvents.maxId &&
        marker.syncEvents.domainMaxIds == checkpoint.syncEvents.domainMaxIds &&
        !marker.syncEvents.requiresFullRecovery &&
        marker.catalog == checkpoint.catalog &&
        marker.prices == checkpoint.prices &&
        marker.history == checkpoint.history &&
        marker.images == checkpoint.images &&
        marker.integrity.totalViolationCount == 0L

private fun validateMarkerMatchesCheckpoint(
    marker: ShopSyncConvergenceMarker,
    checkpoint: ShopSyncRecoveryCheckpoint
) {
    if (!markerMatchesCheckpoint(marker, checkpoint)) {
        throw ShopSyncContractException("recovery_convergence_marker_mismatch")
    }
}

private fun canonicalTimestamp(value: String?): String {
    if (value == null) return "-"
    if (!CANONICAL_TIMESTAMP_PATTERN.matches(value)) {
        throw ShopSyncContractException("recovery_timestamp_invalid")
    }
    return value
}

/**
 * The backend deliberately publishes the preformatted decimal in
 * `price_canonical`; client code must not rebuild its digest input from a
 * binary floating-point value.  We still reject missing or noncanonical wire
 * values before they can be acknowledged by a recovery checkpoint.
 */
private fun canonicalRecoveryPrice(row: InventoryProductPriceRow): String {
    val value = row.priceCanonical ?: throw ShopSyncContractException("recovery_price_canonical_missing")
    if (!CANONICAL_PRICE_PATTERN.matches(value) || value.contains('.') && value.endsWith('0')) {
        throw ShopSyncContractException("recovery_price_canonical_invalid")
    }
    return value
}

/** The server digests the original spelling; this only rejects unknown types. */
private fun validateRecoveryPriceType(value: String) {
    when (value.lowercase(Locale.ROOT)) {
        "purchase", "retail" -> Unit
        else -> throw ShopSyncContractException("recovery_price_type_invalid")
    }
}

/** Mirrors `sync_legacy_timestamp_is_canonical_v1` for the digest-only branch. */
private fun canonicalLegacyTimestampForDigest(value: String?): String {
    if (value == null || !LEGACY_TIMESTAMP_PATTERN.matches(value)) return "invalid"
    return try {
        LocalDateTime.parse(value, LEGACY_TIMESTAMP_FORMATTER)
        value
    } catch (_: Exception) {
        "invalid"
    }
}

/** History also has valid UTC ISO millisecond rows; the digest keeps their original spelling. */
private fun canonicalHistoryTimestampForDigest(value: String?): String {
    if (value == null || value.startsWith("0000-")) return "invalid"
    if (LEGACY_TIMESTAMP_PATTERN.matches(value)) return canonicalLegacyTimestampForDigest(value)
    if (!HISTORY_ISO_MILLISECOND_PATTERN.matches(value)) return "invalid"
    return try {
        LocalDateTime.parse(value, HISTORY_ISO_MILLISECOND_FORMATTER)
        value
    } catch (_: Exception) {
        "invalid"
    }
}

private fun requiredRecoveryDigest(value: String?, code: String): String {
    if (value == null || !SHA256_PATTERN.matches(value)) {
        throw ShopSyncContractException(code)
    }
    return value
}

internal fun decodeRecoveryCheckpointJson(value: String): ShopSyncRecoveryCheckpoint =
    RECOVERY_JSON.decodeFromString(value)

internal fun encodeRecoveryCheckpointJson(value: ShopSyncRecoveryCheckpoint): String =
    RECOVERY_JSON.encodeToString(value)

private fun canonicalUuid(value: String): String = canonicalUuidOrNull(value)
    ?: throw ShopSyncContractException("recovery_uuid_invalid")

private fun canonicalRecoveryEntityUuid(value: String): String =
    canonicalShopSyncRecoveryEntityIdOrNull(value)
        ?: throw ShopSyncContractException("recovery_entity_id_invalid")

private fun canonicalUuidOrNull(value: String): String? {
    val normalized = value.trim().lowercase()
    if (!UUID_PATTERN.matches(normalized)) return null
    return try {
        UUID.fromString(normalized).toString()
    } catch (_: Exception) {
        null
    }
}

private fun retryBackoffMs(attempt: Int): Long {
    val exponent = (attempt - 1).coerceIn(0, 6)
    return min(RETRY_BACKOFF_BASE_MS shl exponent, RETRY_BACKOFF_MAX_MS)
}

private fun recoveryPricePayloadFingerprint(row: InventoryProductPriceRow): String =
    recoveryPricePayloadFingerprint(
        id = canonicalRecoveryEntityUuid(row.id),
        productId = canonicalRecoveryEntityUuid(row.productId),
        type = row.type,
        price = row.price,
        effectiveAt = row.effectiveAt,
        source = row.source,
        note = row.note,
        createdAt = row.createdAt
    )

private fun recoveryPricePayloadFingerprint(
    id: String,
    productId: String,
    type: String,
    price: Double,
    effectiveAt: String,
    source: String?,
    note: String?,
    createdAt: String
): String = listOf(
    id,
    productId,
    type,
    price.toString(),
    effectiveAt,
    source ?: "-",
    note ?: "-",
    createdAt
).joinToString("\u001f")

private fun recoverySupplierPayloadFingerprint(name: String): String = name.trim()

private fun recoveryCategoryPayloadFingerprint(name: String): String = name.trim()

private fun recoveryHistoryPayloadDigest(row: SharedSheetSessionRecord): String {
    val payload = row.toSessionRemotePayload()
    return recoveryHistoryPayloadDigest(
        physicalFingerprint = recoveryHistoryPhysicalFingerprint(
            timestamp = payload.timestamp,
            supplier = payload.supplier,
            category = payload.category,
            isManualEntry = payload.isManualEntry,
            data = payload.data,
            deletedAt = payload.deletedAt
        ),
        sourcePayloadFingerprint = payload.payloadFingerprint()
    )
}

private fun recoveryHistoryPhysicalFingerprint(entry: HistoryEntry): String =
    recoveryHistoryPhysicalFingerprint(
        timestamp = entry.timestamp,
        supplier = entry.supplier,
        category = entry.category,
        isManualEntry = entry.isManualEntry,
        data = entry.data,
        deletedAt = entry.deletedAt
    )

private fun recoveryHistoryPhysicalFingerprint(
    timestamp: String,
    supplier: String,
    category: String,
    isManualEntry: Boolean,
    data: List<List<String>>,
    deletedAt: String?
): String = listOf(
    timestamp,
    supplier,
    category,
    if (isManualEntry) "1" else "0",
    RECOVERY_JSON.encodeToString(data),
    deletedAt ?: "-"
).joinToString("\u001f")

private fun recoveryHistoryPayloadDigest(
    physicalFingerprint: String,
    sourcePayloadFingerprint: String
): String = sha256(
    sha256(physicalFingerprint) + "\u001f" + sha256(sourcePayloadFingerprint)
)

private fun recoveryProductPayloadFingerprint(row: InventoryProductRow): String =
    recoveryProductPayloadFingerprint(
        barcode = row.barcode.trim(),
        itemNumber = row.itemNumber,
        productName = row.productName,
        secondProductName = row.secondProductName,
        purchasePrice = row.purchasePrice,
        retailPrice = row.retailPrice,
        supplierRemoteId = row.supplierId?.let(::canonicalRecoveryEntityUuid),
        categoryRemoteId = row.categoryId?.let(::canonicalRecoveryEntityUuid),
        stockQuantity = row.stockQuantity ?: 0.0,
        primaryImageVersionId = row.primaryImageVersionId?.let(::canonicalUuid),
        primaryImageUpdatedAt = row.primaryImageUpdatedAt
    )

private fun recoveryProductPayloadFingerprint(
    barcode: String,
    itemNumber: String?,
    productName: String?,
    secondProductName: String?,
    purchasePrice: Double?,
    retailPrice: Double?,
    supplierRemoteId: String?,
    categoryRemoteId: String?,
    stockQuantity: Double?,
    primaryImageVersionId: String?,
    primaryImageUpdatedAt: String?
): String = listOf(
    barcode,
    itemNumber ?: "-",
    productName ?: "-",
    secondProductName ?: "-",
    purchasePrice?.toString() ?: "-",
    retailPrice?.toString() ?: "-",
    supplierRemoteId ?: "-",
    categoryRemoteId ?: "-",
    stockQuantity?.toString() ?: "-",
    primaryImageVersionId ?: "-",
    primaryImageUpdatedAt ?: "-"
).joinToString("\u001f")

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private val RECOVERY_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    encodeDefaults = true
}

private const val STAGING_DATABASE_PREFIX = "sync_recovery_stage_"
private const val MANIFEST_VERIFY_PAGE_SIZE = 500
private const val PHYSICAL_VERIFY_PAGE_SIZE = 500
private const val RELATION_VERIFY_PAGE_SIZE = 500
private const val RECOVERY_PAGE_SCHEMA = "shop-sync-recovery-page-v1"
private const val RECOVERY_TAIL_EVENT_PAGE_SCHEMA = "shop-sync-event-page-v1"
private const val RECOVERY_TAIL_EVENT_PAGE_LIMIT = 150
private const val RECOVERY_TAIL_TARGETED_ROWS_SCHEMA = "shop-sync-rows-by-ids-v1"
/** Bounded work per recovery attempt: 20 × 150 frozen events, then durable backoff/retry. */
private const val RECOVERY_TAIL_MAX_PAGES = 20
/** Separately bounds fan-out from complete events to targeted RPCs. */
private const val RECOVERY_TAIL_MAX_TARGETED_CALLS = 1_024
private const val RECOVERY_TAIL_MANIFEST_DELETE_CHUNK = 500
private const val MAX_ORPHAN_STAGING_CLEANUPS_PER_RUN = 8
private const val RETRY_BACKOFF_BASE_MS = 5_000L
private const val RETRY_BACKOFF_MAX_MS = 5 * 60_000L
private val STAGING_NAME_PATTERN = Regex(
    "^${STAGING_DATABASE_PREFIX}[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-" +
        "[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.db$"
)
private val UUID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val CANONICAL_TIMESTAMP_PATTERN = Regex(
    "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z$"
)
private val LEGACY_TIMESTAMP_PATTERN = Regex(
    "^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$"
)
private val LEGACY_TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
private val HISTORY_ISO_MILLISECOND_PATTERN = Regex(
    "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$"
)
private val HISTORY_ISO_MILLISECOND_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withResolverStyle(ResolverStyle.STRICT)
private val CANONICAL_PRICE_PATTERN = Regex("^(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,3})?$")
private val KNOWN_SQLITE_SIDECAR_SUFFIXES = listOf("-journal", "-wal", "-shm")
