package com.example.merchandisecontrolsplitview.data

import android.content.Context
import android.database.Cursor
import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
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
        val nextRetryAtMs: Long?,
        val classification: SyncErrorClassification? = null
    ) : ShopSyncRecoveryResult

    data class Rejected(val code: String) : ShopSyncRecoveryResult
}

/** A persisted restriction, never evidence that the historical failure was transient. */
internal data class LegacyRecoveryResume(
    val runId: String,
    val originalReason: String,
    val checkpointADigest: String
) {
    fun encode() = buildJsonObject {
        put("runId", runId); put("originalReason", originalReason); put("checkpointADigest", checkpointADigest)
    }

    companion object {
        fun decode(value: JsonObject): LegacyRecoveryResume? = runCatching {
            if (value.keys != setOf("runId", "originalReason", "checkpointADigest")) return null
            fun text(key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val run = text("runId")?.takeIf { UUID.fromString(it).toString() == it } ?: return null
            val reason = text("originalReason")?.takeIf { it == "recovery_failed" } ?: return null
            val digest = text("checkpointADigest")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
            LegacyRecoveryResume(run, reason, digest)
        }.getOrNull()
    }
}

private class LegacyRecoveryRun(val resume: LegacyRecoveryResume, val claimID: String,
    val ownerHash: String, val storeScope: String) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LegacyRecoveryRun>
    var finishingCleanup = false
}

/** Local scheduling metadata only. The recovery journal continues to own all scope and staging facts. */
internal data class AutomaticRecoveryRetryMetadata(
    val code: String,
    val category: SyncErrorCategory,
    val windowAttempts: Int,
    val state: String,
    val nextEligibleAtMs: Long,
    val claimID: String,
    val lastAttemptAtMs: Long,
    val lastCompletedAtMs: Long?,
    val legacyResume: LegacyRecoveryResume? = null,
    val processEpoch: String? = null,
    val requalificationObservedAtMs: Long? = null,
    val previousClaimSha256: String? = null
) {
    fun encode(): String = buildJsonObject {
        put("v", if (processEpoch != null) 3 else if (legacyResume == null) 1 else 2); put("kind", "automatic_recovery_retry"); put("code", code)
        put("category", category.name); put("windowAttempts", windowAttempts); put("state", state)
        put("nextEligibleAtMs", nextEligibleAtMs); put("claimID", claimID)
        put("lastAttemptAtMs", lastAttemptAtMs)
        put("lastCompletedAtMs", lastCompletedAtMs?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        if (processEpoch != null) {
            put("legacyResume", legacyResume?.encode() ?: kotlinx.serialization.json.JsonNull)
            put("processEpoch", processEpoch)
            put("requalificationObservedAtMs", requalificationObservedAtMs?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
            put("previousClaimSha256", previousClaimSha256?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        } else legacyResume?.let { put("legacyResume", it.encode()) }
    }.toString()

    val isTransient: Boolean get() = category in setOf(SyncErrorCategory.NetworkOfflineOrTimeout,
        SyncErrorCategory.RateLimited, SyncErrorCategory.RemoteTransient)

    companion object {
        private val fields = setOf("v", "kind", "code", "category", "windowAttempts", "state", "nextEligibleAtMs", "claimID",
            "lastAttemptAtMs", "lastCompletedAtMs")
        fun decode(raw: String): AutomaticRecoveryRetryMetadata? = runCatching {
            if (raw.length > 2_048) return null
            val value = Json.parseToJsonElement(raw) as? JsonObject ?: return null
            fun text(key: String): String? = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun number(key: String): JsonPrimitive? = (value[key] as? JsonPrimitive)?.takeUnless { it.isString }
            val version = number("v")?.intOrNull
            val legacy = when (version) {
                1 -> { if (value.keys != fields) return null; null }
                2 -> {
                    if (value.keys != fields + "legacyResume") return null
                    LegacyRecoveryResume.decode(value["legacyResume"] as? JsonObject ?: return null) ?: return null
                }
                3 -> {
                    if (value.keys != fields + setOf("legacyResume", "processEpoch", "requalificationObservedAtMs", "previousClaimSha256")) return null
                    if (value["legacyResume"] == kotlinx.serialization.json.JsonNull) null
                    else LegacyRecoveryResume.decode(value["legacyResume"] as? JsonObject ?: return null) ?: return null
                }
                else -> return null
            }
            if (text("kind") != "automatic_recovery_retry") return null
            val code = text("code")?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,160}")) } ?: return null
            val category = SyncErrorCategory.entries.firstOrNull { it.name == text("category") } ?: return null
            val attempts = number("windowAttempts")?.intOrNull?.takeIf { it in 1..5 } ?: return null
            val state = text("state")?.takeIf { it in setOf("in_flight", "waiting", "cooldown", "blocked", "requalifying") } ?: return null
            val next = number("nextEligibleAtMs")?.longOrNull?.takeIf { it >= 0 } ?: return null
            val claim = text("claimID")?.takeIf { UUID.fromString(it).toString() == it } ?: return null
            val started = number("lastAttemptAtMs")?.longOrNull?.takeIf { it >= 0 } ?: return null
            val completed = number("lastCompletedAtMs")?.longOrNull
            val epoch = if (version == 3) text("processEpoch")?.takeIf { UUID.fromString(it).toString() == it } ?: return null else null
            val observed = number("requalificationObservedAtMs")?.longOrNull
            val origin = text("previousClaimSha256")
            if (state == "requalifying") {
                if (version != 3 || completed != null || value["lastCompletedAtMs"] != kotlinx.serialization.json.JsonNull ||
                    observed == null || observed < 0 || observed > Long.MAX_VALUE - 300_000L || next < observed + 300_000L ||
                    origin == null || !origin.matches(Regex("[a-f0-9]{64}"))) return null
            } else if (version == 3 && (value["requalificationObservedAtMs"] != kotlinx.serialization.json.JsonNull ||
                value["previousClaimSha256"] != kotlinx.serialization.json.JsonNull)) return null
            if (state == "in_flight") {
                if (value["lastCompletedAtMs"] != kotlinx.serialization.json.JsonNull || next < started) return null
            } else if (state != "requalifying") {
                if (completed == null || completed < started || next < completed) return null
                if (state == "cooldown" && (completed > Long.MAX_VALUE - 300_000L || next < completed + 300_000L)) return null
            }
            val metadata = AutomaticRecoveryRetryMetadata(code, category, attempts, state, next, claim, started, completed, legacy,
                epoch, observed, origin)
            // Only our canonical encoding is admitted; duplicate keys and ambiguous numeric forms fail closed.
            metadata.takeIf { it.encode() == raw && (state != "cooldown" || attempts == 5) }
        }.getOrNull()
    }
}

/** Per invocation only. The transport supplies the actual client timeout before each request. */
private class RecoveryScopeResolutionContinuation(
    val awaitResolution: suspend (remainingMs: Long) -> Boolean
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RecoveryScopeResolutionContinuation>
    var requestStartedAtNanos: Long? = null
    var requestTimeoutMs: Long? = null

    suspend fun awaitWithinRequestBudget(): Boolean {
        val started = requestStartedAtNanos ?: return false
        val timeout = requestTimeoutMs?.takeIf { it > 0L } ?: return false
        val elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0L)
        if (elapsed >= timeout) return false
        val remaining = timeout - elapsed
        return withTimeoutOrNull(remaining) { awaitResolution(remaining) } == true
    }
}

/** Also used by synthetic transports to declare their own bounded request budget explicitly. */
internal suspend fun recordRecoveryRpcTimeout(timeoutMs: Long) {
    val continuation = coroutineContext[RecoveryScopeResolutionContinuation] ?: return
    continuation.requestStartedAtNanos = System.nanoTime()
    continuation.requestTimeoutMs = timeoutMs.takeIf { it > 0L && it < Long.MAX_VALUE }
}

internal object ShopSyncRecoveryTestHooks {
    /** Monotonic observation of the actual Room writer transaction, including commit/rollback. */
    @Volatile
    var activationWriterTiming: ((String, Long) -> Unit)? = null

    /** The whole acquired business transition fence, distinct from its Room transaction. */
    @Volatile
    var activationBoundaryTiming: ((String, Long) -> Unit)? = null

    /** Includes flight cancellation/quiescing while new local writers are unavailable. */
    @Volatile
    var activationTransitionTiming: ((String, Long) -> Unit)? = null

    @Volatile
    var timingObservationError: String? = null

    fun observeTiming(observer: ((String, Long) -> Unit)?, event: String, atNanos: Long? = null) {
        if (observer == null) return
        try {
            observer(event, atNanos ?: SystemClock.elapsedRealtimeNanos())
        } catch (error: Exception) {
            // A diagnostic recorder must never change a successful commit or mask its failure.
            timingObservationError = error.javaClass.simpleName
        }
    }

    /** Observation only; never changes admission, deadlines or the result of a scope check. */
    @Volatile
    var onScopeResolutionWaiting: (() -> Unit)? = null

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
        activationWriterTiming = null
        activationBoundaryTiming = null
        activationTransitionTiming = null
        timingObservationError = null
        onScopeResolutionWaiting = null
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
        { targetContext, name -> targetContext.deleteDatabase(name) },
    private val automaticRetryJitter: () -> Double = { kotlin.random.Random.nextDouble(0.5, 1.5) }
) {
    private val appContext = context.applicationContext

    /** Read-only admission: no preparation, repair, migration, deletion or new generation. */
    suspend fun qualifyLegacyAutomaticRecovery(
        journal: SyncRecoveryJournal, accountId: String, selectedShop: SelectedShop,
        activeScope: Task126OwnerStoreScope, existing: LegacyRecoveryResume? = null
    ): LegacyRecoveryResume? = try {
        val shop = canonicalUuidOrNull(selectedShop.shopId)
        val run = journal.runId
        val digest = journal.checkpointADigest
        if (!remote.isConfigured || shop == null || run == null || digest == null ||
            journal.authorizationMode != SyncRecoveryAuthorizationModes.SAME_SCOPE ||
            journal.ownerHash != activeScope.ownerHash || journal.storeScope != activeScope.storeId ||
            journal.shopId != shop ||
            Task126OwnerStoreGate.validate(activeScope, task126ActiveOwnerStoreScope(accountId, selectedShop)) !=
                Task126OwnerStoreGateDecision.Allowed || !leaseStillValid(accountId, shop, journal.deviceId)) null
        else {
            val candidate = existing ?: LegacyRecoveryResume.decode(buildJsonObject {
                put("runId", run); put("originalReason", journal.reason); put("checkpointADigest", digest)
            })
            val legacyShape = existing != null || (journal.reason == "recovery_failed" && journal.attemptCount == 5 &&
                journal.phase == SyncRecoveryJournalPhases.STAGING && journal.createdAtMs >= 0L &&
                journal.updatedAtMs >= journal.createdAtMs && journal.updatedAtMs <= Long.MAX_VALUE - 300_000L &&
                journal.nextRetryAtMs?.let { it >= journal.createdAtMs } == true)
            val binding = activeDb.businessDataScopeBindingDao().get()?.toOwnerStoreScope()
            val baseline = activeDb.syncRecoveryBaselineDao().get()
            if (!legacyShape || candidate == null || candidate.runId != run || candidate.checkpointADigest != digest ||
                (existing != null && AutomaticRecoveryRetryMetadata.decode(journal.reason)?.legacyResume != existing) ||
                binding == null || Task126OwnerStoreGate.validate(binding, activeScope) != Task126OwnerStoreGateDecision.Allowed ||
                baseline == null || baseline.ownerHash != activeScope.ownerHash || baseline.storeScope != activeScope.storeId ||
                baseline.shopId != shop || baseline.deviceId != journal.deviceId ||
                activeDb.syncEventOutboxDao().countAll() != activeDb.syncEventOutboxDao().countPendingForScope(accountId, activeScope.storeId)) null
            else {
                validateShopSyncActiveReceipt(activeDb, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
                if (journal.phase == SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING && existing != null) {
                    if (baseline.generationId != run) throw ShopSyncContractException("legacy_recovery_active_generation_changed")
                } else {
                    if (journal.phase !in setOf(SyncRecoveryJournalPhases.STAGING, SyncRecoveryJournalPhases.READY_TO_ACTIVATE) ||
                        baseline.generationId == run) throw ShopSyncContractException("legacy_recovery_generation_invalid")
                    val name = "$STAGING_DATABASE_PREFIX$run.db"
                    val file = validatedStagingFile(name)
                    if (journal.stagingDatabaseName != name || file?.isFile != true || file.name != name) {
                        throw ShopSyncContractException("legacy_recovery_staging_missing")
                    }
                    // Reject old/unknown schemas before Room can run any migration or create an absent file.
                    android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {
                        if (it.version != activeDb.openHelper.readableDatabase.version) {
                            throw ShopSyncContractException("legacy_recovery_staging_schema_changed")
                        }
                    }
                    val stage = openStagingDatabase(name)
                    try { validateLegacyAcceptedPrefix(stage, journal, accountId, journal.deviceId) }
                    finally { stage.close() }
                }
                candidate.takeIf { activeDb.syncRecoveryJournalDao().get() == journal &&
                    leaseStillValid(accountId, shop, journal.deviceId) }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { null }

    /** Read-only admission for an orphaned claim. No retry budget, remote request or journal repair here. */
    suspend fun qualifyInterruptedAutomaticRecovery(
        journal: SyncRecoveryJournal, accountId: String, selectedShop: SelectedShop,
        activeScope: Task126OwnerStoreScope
    ): Boolean = try {
        val metadata = AutomaticRecoveryRetryMetadata.decode(journal.reason)
        val shop = canonicalUuidOrNull(selectedShop.shopId)
        require(metadata != null && metadata.state in setOf("in_flight", "requalifying") &&
            journal.attemptCount >= metadata.windowAttempts && journal.attemptCount > 0 &&
            journal.createdAtMs >= 0 && journal.updatedAtMs >= journal.createdAtMs &&
            shop != null && remote.isConfigured && journal.shopId == shop &&
            journal.ownerHash == activeScope.ownerHash && journal.storeScope == activeScope.storeId &&
            journal.authorizationMode == SyncRecoveryAuthorizationModes.SAME_SCOPE &&
            Task126OwnerStoreGate.validate(activeScope, task126ActiveOwnerStoreScope(accountId, selectedShop)) ==
                Task126OwnerStoreGateDecision.Allowed && leaseStillValid(accountId, shop, journal.deviceId))
        val binding = requireNotNull(activeDb.businessDataScopeBindingDao().get()).toOwnerStoreScope()
        require(Task126OwnerStoreGate.validate(binding, activeScope) == Task126OwnerStoreGateDecision.Allowed)
        val baseline = requireNotNull(activeDb.syncRecoveryBaselineDao().get())
        require(baseline.ownerHash == activeScope.ownerHash && baseline.storeScope == activeScope.storeId &&
            baseline.shopId == shop && baseline.deviceId == journal.deviceId &&
            activeDb.syncEventOutboxDao().countAll() == activeDb.syncEventOutboxDao().countPendingForScope(accountId, activeScope.storeId))
        validateShopSyncActiveReceipt(activeDb, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson))
        when (journal.phase) {
            SyncRecoveryJournalPhases.REQUIRED -> require(journal.runId == null && journal.stagingDatabaseName == null &&
                journal.checkpointADigest == null && journal.checkpointBDigest == null)
            SyncRecoveryJournalPhases.STAGING, SyncRecoveryJournalPhases.READY_TO_ACTIVATE -> {
                val run = requireNotNull(journal.runId)
                require(run != baseline.generationId && journal.checkpointADigest != null)
                val name = "$STAGING_DATABASE_PREFIX$run.db"
                val file = requireNotNull(validatedStagingFile(name))
                require(journal.stagingDatabaseName == name && file.isFile)
                android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {
                    require(it.version == activeDb.openHelper.readableDatabase.version)
                }
                val stage = openStagingDatabase(name)
                try { validateLegacyAcceptedPrefix(stage, journal, accountId, journal.deviceId, allowEmptyPrefix = true) }
                finally { stage.close() }
            }
            SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING -> require(journal.runId == baseline.generationId)
            else -> error("interrupted_recovery_phase_unqualified")
        }
        activeDb.syncRecoveryJournalDao().get() == journal && leaseStillValid(accountId, shop, journal.deviceId)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { false }

    private suspend fun validateLegacyAcceptedPrefix(
        stage: AppDatabase, journal: SyncRecoveryJournal, accountId: String, deviceId: String,
        allowEmptyPrefix: Boolean = false
    ) {
        val run = requireNotNull(journal.runId)
        val sql = stage.openHelper.readableDatabase
        val progress = RecoveryPageProgress(sql)
        val checkpoint = progress.checkpoint(journal)
        validateRecoveryScopeIdentity(checkpoint.scope, accountId, deviceId)
        validateRecoveryCheckpointResourceBounds(checkpoint, resourceLimits)
        if (checkpoint.status != "ready" || checkpoint.integrity.totalViolationCount != 0L ||
            checkpoint.shopId != journal.shopId) throw ShopSyncContractException("legacy_recovery_checkpoint_invalid")
        val budget = RecoveryTransferBudget(resourceLimits)
        var unfinished = false
        var acceptedRows = 0L
        var acceptedDomains = 0
        for (domain in ShopSyncRowDomain.entries) {
            val accepted = progress.accepted(domain)
            val count = stage.syncRecoveryManifestDao().count(run, domain.wireValue).toLong()
            if (accepted == null) {
                if (count != 0L) throw ShopSyncContractException("legacy_recovery_manifest_without_progress")
                unfinished = true
                continue
            }
            if (unfinished || accepted.rows != count) throw ShopSyncContractException("legacy_recovery_prefix_invalid")
            budget.restore(domain, accepted)
            acceptedRows = checkedRecoveryAdd(acceptedRows, accepted.rows, "legacy_recovery_prefix_overflow")
            acceptedDomains++
            if (!accepted.complete) {
                val last = sql.query("SELECT MAX(remoteId) FROM sync_recovery_manifest WHERE generationId=? AND domain=?",
                    arrayOf(run, domain.wireValue)).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getString(0)
                }
                if (last != accepted.afterId) throw ShopSyncContractException("legacy_recovery_cursor_invalid")
                unfinished = true
            }
            if (domain == ShopSyncRowDomain.IMAGES) {
                // Image metadata has no physical Room body. Revalidate its canonical receipt;
                // product/image relations can legitimately straddle live pages until the frozen tail.
                val images = RecoveryManifestReader(stage.syncRecoveryManifestDao(), run, domain)
                while (true) {
                    val image = images.next() ?: break
                    val parts = image.versionLine.split('\u001f')
                    if (parts.size != 15) throw ShopSyncContractException("legacy_recovery_image_receipt_invalid")
                    fun variant(start: Int) = ShopSyncImageVariantRow(parts[start], parts[start + 1].toLong(),
                        parts[start + 2].toInt(), parts[start + 3].toInt(), parts[start + 4])
                    val canonical = ShopSyncRows.Images(listOf(ShopSyncImageRow(parts[0], accountId, journal.shopId,
                        parts[3].takeUnless { it == "-" }, parts[1], parts[2], parts[4], variant(5), variant(10))))
                        .toManifestRows(run, domain).single()
                    if (image != canonical) throw ShopSyncContractException("legacy_recovery_image_receipt_invalid")
                }
            }
        }
        fun count(table: String) = sql.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getLong(0) }
        if ((!allowEmptyPrefix && acceptedRows == 0L) || count("sync_recovery_manifest") != acceptedRows ||
            count("recovery_transfer_progress") != acceptedDomains.toLong()) {
            throw ShopSyncContractException("legacy_recovery_prefix_missing")
        }
        // A current claim can die after checkpoint A, before accepting any page.
        // Its checkpoint still proves the scope; no business body may exist yet.
        if (acceptedRows == 0L && listOf("products", "suppliers", "categories", "product_prices", "history_entries",
                "product_remote_refs", "supplier_remote_refs", "category_remote_refs", "history_entry_remote_refs")
                .any { count(it) != 0L }) {
            throw ShopSyncContractException("interrupted_recovery_empty_prefix_has_rows")
        }
        sql.query("PRAGMA foreign_key_check").use {
            if (it.moveToFirst()) throw ShopSyncContractException("legacy_recovery_prefix_foreign_key")
        }
        validatePhysicalSnapshot(stage, run, resourceLimits.historyPageRows)
    }

    suspend fun recover(
        accountId: String,
        selectedShop: SelectedShop,
        activeScope: Task126OwnerStoreScope,
        automaticClaimID: String? = null,
        awaitScopeResolution: (suspend (remainingMs: Long) -> Boolean)? = null
    ): ShopSyncRecoveryResult = withContext(Dispatchers.IO +
        (if (automaticClaimID != null && awaitScopeResolution != null)
            RecoveryScopeResolutionContinuation(awaitScopeResolution) else EmptyCoroutineContext)) {
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
        val automaticMetadata = AutomaticRecoveryRetryMetadata.decode(journal.reason)
        if ((automaticClaimID != null && (automaticMetadata?.claimID != automaticClaimID || automaticMetadata.state != "in_flight")) ||
            (automaticClaimID == null && journal.reason.trimStart().startsWith("{"))) {
            return@withContext ShopSyncRecoveryResult.Rejected("recovery_automatic_claim_invalid")
        }
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
        val legacyResume = automaticMetadata?.legacyResume
        withContext(legacyResume?.let { LegacyRecoveryRun(it, requireNotNull(automaticClaimID), journal.ownerHash, journal.storeScope) }
            ?: EmptyCoroutineContext) legacyOperation@ {
        if (legacyResume != null && qualifyLegacyAutomaticRecovery(journal, accountId, selectedShop,
                activeScope, legacyResume) != legacyResume) {
            return@legacyOperation ShopSyncRecoveryResult.Rejected("legacy_recovery_prefix_invalid")
        }
        if (!remote.isConfigured) {
            return@legacyOperation retryAfterFailure(
                journal = journal,
                code = "shop_sync_reader_unavailable",
                keepActivatedPhase =
                    journal.phase == SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING,
                expectedRunId = journal.runId,
                retainStagingDatabase = true,
                automaticClaimID = automaticClaimID
            )
        }

        if (journal.phase == SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING) {
            return@legacyOperation resumeActivatedCleanupWithRetry(
                accountId,
                shopId,
                activeScope,
                journal,
                automaticClaimID
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
        if (legacyResume != null && resumedDb == null) {
            return@legacyOperation ShopSyncRecoveryResult.Rejected("legacy_recovery_staging_missing")
        }
        if (resumedDb == null && !cleanupKnownStaging(journal.stagingDatabaseName)) {
            return@legacyOperation retryAfterFailure(journal, "recovery_staging_cleanup_deferred", false,
                journal.runId, retainStagingDatabase = true, automaticClaimID = automaticClaimID)
        }
        if (legacyResume == null && !cleanupBoundedOrphanStaging(excluding = ownedName.takeIf { resumedDb != null })) {
            resumedDb?.close()
            return@legacyOperation retryAfterFailure(journal, "recovery_orphan_cleanup_deferred", false,
                journal.runId, retainStagingDatabase = resumedDb != null, automaticClaimID = automaticClaimID)
        }

        val replaceConfirmed = journal.authorizationMode ==
            SyncRecoveryAuthorizationModes.MISMATCH_REPLACE_CONFIRMED
        if (!replaceConfirmed) {
            val binding = activeDb.businessDataScopeBindingDao().get()?.toOwnerStoreScope()
            val scopedOutbox = activeDb.syncEventOutboxDao().countPendingForScope(accountId, activeScope.storeId)
            if (binding == null || Task126OwnerStoreGate.validate(binding, activeScope) != Task126OwnerStoreGateDecision.Allowed ||
                activeDb.syncEventOutboxDao().countAll() != scopedOutbox) {
                resumedDb?.close()
                if (legacyResume != null) return@legacyOperation ShopSyncRecoveryResult.Rejected("legacy_recovery_local_scope_changed")
                return@legacyOperation retryAfterFailure(journal, "recovery_local_pending", false,
                    expectedRunId = journal.runId, retainStagingDatabase = false, automaticClaimID = automaticClaimID)
            }
        }
        val generationId = if (legacyResume != null) legacyResume.runId
            else if (resumedDb != null) requireNotNull(journal.runId) else UUID.randomUUID().toString().lowercase()
        val stagingName = "$STAGING_DATABASE_PREFIX$generationId.db"
        val stagingFile = validatedStagingFile(stagingName)
            ?: return@legacyOperation ShopSyncRecoveryResult.Rejected("recovery_staging_path_invalid")
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
            if (legacyResume != null) {
                val current = fetchCheckpoint(accountId, shopId, currentDevice, checkpointA.scope,
                    checkpointA.syncEvents.maxId, checkpointA.scope.key)
                validateCheckpointTailBoundary(checkpointA, current, shopId)
                if (current.integrity.totalViolationCount != 0L || current.syncEvents.requiresFullRecovery ||
                    (!sameRecoveryMaterial(checkpointA, current) && current.syncEvents.maxId == checkpointA.syncEvents.maxId)) {
                    throw ShopSyncContractException("legacy_recovery_checkpoint_changed")
                }
                // Awaiting the checkpoint cannot replace or corrupt the accepted local prefix.
                validateLegacyAcceptedPrefix(requireNotNull(stagingDb), requireOwnedJournal(generationId), accountId, currentDevice)
            }
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
                if (legacyResume != null) throw ShopSyncContractException("legacy_recovery_post_activation_checkpoint_changed")
                val cleanupJournal = requireOwnedJournal(generationId)
                stagingDb.close()
                stagingDb = null
                val cleanupComplete = cleanupKnownStaging(stagingName)
                return@legacyOperation retryAfterFailure(
                    journal = cleanupJournal,
                    code = ShopSyncRecoveryReasons.POST_ACTIVATION_CHECKPOINT_CHANGED,
                    keepActivatedPhase = false,
                    expectedRunId = generationId,
                    retainStagingDatabase = !cleanupComplete,
                    checkpointADigest = markerAfterActivation.checkpointDigest,
                    automaticClaimID = automaticClaimID
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
                return@legacyOperation retryAfterFailure(
                    journal = activeDb.syncRecoveryJournalDao().get()
                        ?: throw ShopSyncContractException("recovery_cleanup_journal_lost"),
                    code = "recovery_staging_cleanup_deferred",
                    keepActivatedPhase = true,
                    expectedRunId = generationId,
                    retainStagingDatabase = true,
                    automaticClaimID = automaticClaimID
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
                val resumable = legacyResume != null || hasAcceptedRecoveryHeader(stagingName, latest)
                val cleanupComplete = durablyActivated || resumable || cleanupKnownStaging(stagingName)
                retryAfterFailure(
                    journal = latest,
                    code = "recovery_cancelled",
                    keepActivatedPhase = durablyActivated,
                    expectedRunId = generationId,
                    retainStagingDatabase = !cleanupComplete || durablyActivated || resumable,
                    automaticClaimID = automaticClaimID
                )
            }
            throw cancelled
        } catch (error: Exception) {
            ShopSyncRecoveryTestHooks.onRecoveryFailure?.invoke(error)
            val contract = error as? ShopSyncContractException
            val classification = SyncErrorClassifier.classify(error, nowMs())
            val code = contract?.code
                ?: classification.httpStatus?.let { "shop_sync_rpc_http_$it" }
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
            // Invalid/expired protocol fences require a new A. A local failure
            // does not itself invalidate accepted pages; retry eligibility is
            // still decided separately from their integrity.
            val typedTransient = classification.category in setOf(SyncErrorCategory.NetworkOfflineOrTimeout,
                SyncErrorCategory.RateLimited, SyncErrorCategory.RemoteTransient)
            // Retry-After eligibility is separate from the integrity of pages already accepted under this fence.
            val resumable = legacyResume != null || (!durablyActivated && (contract == null || typedTransient) &&
                hasAcceptedRecoveryHeader(stagingName, latest))
            val cleanupComplete = durablyActivated || resumable || cleanupKnownStaging(stagingName)
            val retry = retryAfterFailure(
                journal = latest,
                code = code,
                keepActivatedPhase = durablyActivated,
                expectedRunId = generationId,
                retainStagingDatabase = !cleanupComplete || durablyActivated || resumable,
                automaticClaimID = automaticClaimID,
                classification = classification
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
    }

    private suspend fun resumeActivatedCleanupWithRetry(
        accountId: String,
        shopId: String,
        activeScope: Task126OwnerStoreScope,
        journal: SyncRecoveryJournal,
        automaticClaimID: String?
    ): ShopSyncRecoveryResult = try {
        finishActivatedCleanup(accountId, shopId, activeScope, journal, automaticClaimID)
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            retryAfterFailure(
                journal = journal,
                code = "recovery_cancelled",
                keepActivatedPhase = true,
                expectedRunId = journal.runId,
                retainStagingDatabase = true,
                automaticClaimID = automaticClaimID
            )
        }
        throw cancelled
    } catch (error: Exception) {
        logger("shop_sync_recovery cleanup_failed type=${error::class.java.simpleName}")
        val classification = SyncErrorClassifier.classify(error, nowMs())
        retryAfterFailure(
            journal = journal,
            code = (error as? ShopSyncContractException)?.code
                ?: classification.httpStatus?.let { "shop_sync_rpc_http_$it" }
                ?: error.message?.takeIf { it.startsWith("recovery_") }
                ?: ShopSyncRecoveryReasons.RECOVERY_FAILED,
            keepActivatedPhase = true,
            expectedRunId = journal.runId,
            retainStagingDatabase = true,
            automaticClaimID = automaticClaimID,
            classification = classification
        )
    }

    private suspend fun finishActivatedCleanup(
        accountId: String,
        shopId: String,
        activeScope: Task126OwnerStoreScope,
        journal: SyncRecoveryJournal,
        automaticClaimID: String?
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
            if (coroutineContext[LegacyRecoveryRun] != null) {
                throw ShopSyncContractException("legacy_recovery_post_activation_checkpoint_changed")
            }
            val cleanupComplete = cleanupKnownStaging(journal.stagingDatabaseName)
            return retryAfterFailure(
                journal = journal,
                code = ShopSyncRecoveryReasons.POST_ACTIVATION_CHECKPOINT_CHANGED,
                keepActivatedPhase = false,
                expectedRunId = journal.runId,
                retainStagingDatabase = !cleanupComplete,
                checkpointADigest = marker.checkpointDigest,
                automaticClaimID = automaticClaimID
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
                retainStagingDatabase = true,
                automaticClaimID = automaticClaimID
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
        if (!leaseAfterSuccessfulRpc(accountId, shopId, deviceId)) {
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
        if (!leaseAfterSuccessfulRpc(accountId, shopId, deviceId)) {
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
            if (!leaseAfterSuccessfulRpc(accountId, shopId, deviceId)) {
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
            if (!leaseAfterSuccessfulRpc(accountId, shopId, deviceId)) {
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
            if (!leaseAfterSuccessfulRpc(accountId, shopId, deviceId)) {
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
        val writerObserver = ShopSyncRecoveryTestHooks.activationWriterTiming
        var writerObserved = false
        try {
        activeDb.withTransaction {
            if (writerObserver != null) {
                writerObserved = true
                ShopSyncRecoveryTestHooks.observeTiming(writerObserver, "acquired")
            }
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
            val previousResolutionBaseline = if (sameScope) activeDb.syncRecoveryBaselineDao().get() else null
            val preservedResolutionEntries = if (sameScope) readLocalBusinessEntries(activeDb, accountId, activeScope.storeId) else emptyList()
            val resolutionSnapshot = if (sameScope) loadBusinessResolutionSnapshot(activeDb, accountId, activeScope.storeId,
                preservedResolutionEntries) else null
            val overlay = if (sameScope) SameScopeRecoveryOverlay(mainSql, resolutionSnapshot,
                preservedResolutionEntries.filter { it.eventType == BUSINESS_WRITE_EVENT &&
                    resolutionSnapshot?.supersededAttempt(it) == true }.map { it.id }.toSet()).also { it.capture() } else null
            val retainedBusinessTables = overlay?.unchangedBusinessTablesToRetain().orEmpty()
            BUSINESS_DELETE_ORDER.filterNot { it in retainedBusinessTables }
                .forEach { mainSql.execSQL("DELETE FROM `$it`") }
            BUSINESS_COPY_ORDER.forEach { spec ->
                if (spec.table !in retainedBusinessTables) {
                    if (overlay != null) overlay.copyRemoteTable(spec.table) else copyTable(stageSql, mainSql, spec)
                }
                ShopSyncRecoveryTestHooks.afterActiveTableCopied?.invoke(spec.table)
            }
            if (overlay != null) overlay.restorePending()
            else {
                mainSql.execSQL("DELETE FROM `pending_catalog_tombstones`")
                mainSql.execSQL("DELETE FROM `sync_event_outbox`")
            }
            val advancedEqualManifest = sameScope && advanceEqualRecoveryManifestGeneration(
                mainSql, previousResolutionBaseline?.generationId, runId)
            mainSql.execSQL("DELETE FROM `sync_event_apply_status`")
            mainSql.execSQL("DELETE FROM `sync_event_watermarks`")
            mainSql.execSQL("DELETE FROM `business_data_scope_binding`")
            mainSql.execSQL("DELETE FROM `sync_recovery_baseline`")
            if (!advancedEqualManifest) mainSql.execSQL("DELETE FROM `sync_recovery_manifest`")
            if (sameScope) {
                for (spec in listOf(BASELINE_TABLE, MANIFEST_TABLE)) {
                    if (spec == MANIFEST_TABLE && advancedEqualManifest) continue
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
            if (sameScope) appendBusinessResolutionTransfer(activeDb, accountId, previousResolutionBaseline, preservedResolutionEntries)
            overlay?.close()
            sourceTables.asReversed().forEach { mainSql.execSQL("DROP TABLE `$it`") }
        }
        } finally {
            if (writerObserved) ShopSyncRecoveryTestHooks.observeTiming(writerObserver, "released")
        }
    }

    private suspend fun leaseStillValid(
        accountId: String,
        shopId: String,
        deviceId: String
    ): Boolean {
        if (!scopeStillValid(accountId, shopId) || activeDb.syncEventDeviceStateDao().get()?.deviceId != deviceId) return false
        val legacy = coroutineContext[LegacyRecoveryRun] ?: return true
        val journal = activeDb.syncRecoveryJournalDao().get()
        if (journal == null) return legacy.finishingCleanup && activeDb.syncRecoveryBaselineDao().get()?.generationId == legacy.resume.runId
        val metadata = AutomaticRecoveryRetryMetadata.decode(journal.reason) ?: return false
        if (metadata.legacyResume != legacy.resume || metadata.claimID != legacy.claimID || metadata.state != "in_flight" ||
            journal.runId != legacy.resume.runId || journal.checkpointADigest != legacy.resume.checkpointADigest ||
            journal.shopId != shopId || journal.deviceId != deviceId || journal.ownerHash != legacy.ownerHash ||
            journal.storeScope != legacy.storeScope) return false
        if (journal.phase == SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING) {
            return activeDb.syncRecoveryBaselineDao().get()?.generationId == legacy.resume.runId
        }
        val name = "$STAGING_DATABASE_PREFIX${legacy.resume.runId}.db"
        return journal.phase in setOf(SyncRecoveryJournalPhases.STAGING, SyncRecoveryJournalPhases.READY_TO_ACTIVATE) &&
            journal.stagingDatabaseName == name && validatedStagingFile(name)?.isFile == true
    }

    /** Only called after a successful response and outside transactions; direct callers have no continuation. */
    private suspend fun leaseAfterSuccessfulRpc(accountId: String, shopId: String, deviceId: String): Boolean {
        if (leaseStillValid(accountId, shopId, deviceId)) return true
        val resolved = coroutineContext[RecoveryScopeResolutionContinuation]?.awaitWithinRequestBudget() == true
        return resolved && leaseStillValid(accountId, shopId, deviceId)
    }

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
            coroutineContext[LegacyRecoveryRun]?.finishingCleanup = true
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
        checkpointADigest: String? = null,
        automaticClaimID: String? = null,
        classification: SyncErrorClassification? = null
    ): ShopSyncRecoveryResult.RetryRequired {
        var persistedRetryAt: Long? = null
        var observedRetryJournal: SyncRecoveryJournal? = null
        ShopSyncRecoveryTestHooks.beforeRetryTransaction?.invoke()
        activeDb.withTransaction {
            val current = activeDb.syncRecoveryJournalDao().get()
            observedRetryJournal = current
            val automaticMetadata = current?.let { AutomaticRecoveryRetryMetadata.decode(it.reason) }
            if (
                current == journal &&
                current.ownerHash == journal.ownerHash &&
                current.storeScope == journal.storeScope &&
                current.deviceId == journal.deviceId &&
                current.runId == expectedRunId &&
                (automaticClaimID == null || automaticMetadata?.claimID == automaticClaimID)
            ) {
                val nextAttempt = if (automaticClaimID == null) nextSyncRecoveryAttemptCount(current.attemptCount)
                    else current.attemptCount
                val localDelay = if (automaticClaimID == null) retryBackoffMs(nextAttempt) else {
                    val jitter = automaticRetryJitter()
                    require(jitter.isFinite() && jitter in 0.5..1.5) { "recovery_retry_jitter_invalid" }
                    val failureNumber = requireNotNull(automaticMetadata).windowAttempts
                    minOf(60_000L, ((2_000L shl (failureNumber - 1)) * jitter).toLong())
                }
                val retryAt = maxOf(nowMs() + localDelay, classification?.retryAfterAtMs ?: 0L)
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
                            reason = if (automaticClaimID == null) code else current.reason,
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
        return ShopSyncRecoveryResult.RetryRequired(code, retryAt, classification)
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
    val startupTrace = coroutineContext[LocalStartupTrace]
    startupTrace.cost(StartupCostStage.RECEIPT_HEADER) {
        if (checkpoint.schemaVersion != "shop-sync-recovery-checkpoint-v1" || checkpoint.status != "ready" ||
            checkpoint.integrity.totalViolationCount != 0L || checkpoint.syncEvents.requiresFullRecovery) {
            throw ShopSyncContractException("ordinary_canonical_receipt_invalid")
        }
    }
    startupTrace.cost(StartupCostStage.RECEIPT_RELATIONAL) { validateRelationalManifest(db, generationId) }
    startupTrace.cost(StartupCostStage.RECEIPT_MANIFEST) { validateManifest(db, generationId, checkpoint) }
    // Also prove every retained/active price parent in the same generation, without reading physical A bodies.
    startupTrace.cost(StartupCostStage.RECEIPT_PRICE_PARENTS) { materializablePriceCount(db, generationId) }
}

/** Shared complete local proof; the active store deliberately retains History tombstone bodies. */
internal suspend fun validateShopSyncActiveReceipt(
    db: AppDatabase,
    generationId: String,
    checkpoint: ShopSyncRecoveryCheckpoint
) {
    checkOrdinaryNoWorkReceiptPriority(db, "receipt_begin")
    validateHistoryTombstoneShadows(db, generationId)
    validateShopSyncCanonicalReceipt(db, generationId, checkpoint)
    validateStagingDatabase(db, generationId, checkpoint, activeStoreHistory = true,
        localAcknowledgements = true, localOverlay = true)
    validatePhysicalSnapshot(db, generationId, activeStoreHistory = true,
        localOverlay = true, localAcknowledgements = true)
    checkOrdinaryNoWorkReceiptPriority(db, "receipt_end")
}

private data class HistoryTombstoneShadow(val uid: Long, val remoteId: String, val deletedAt: String)

/** Scan every deleted physical row, including orphan bridges; never load its retained old body. */
private suspend fun validateHistoryTombstoneShadows(db: AppDatabase, generationId: String) {
    var afterUid: Long? = null
    do {
        coroutineContext.ensureActive()
        checkOrdinaryNoWorkReceiptPriority(db, "history_page")
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
                    checkOrdinaryNoWorkReceiptPriority(db, "history_row")
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
        checkOrdinaryNoWorkReceiptPriority(db, "history_query_end")
        val manifests = db.syncRecoveryManifestDao().getByRemoteIds(
            generationId, ShopSyncRowDomain.HISTORY.wireValue, rows.map { it.remoteId }
        ).associateBy { it.remoteId }
        rows.forEach { shadow ->
            checkOrdinaryNoWorkReceiptPriority(db, "history_manifest_row")
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
    val startupTrace = coroutineContext[LocalStartupTrace]
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
            checkOrdinaryNoWorkReceiptPriority(db, "canonical_page")
            val rows = startupTrace.cost(StartupCostStage.MANIFEST_FETCH, domain) {
                db.syncRecoveryManifestDao().page(
                    generationId = generationId,
                    domain = domain.wireValue,
                    afterId = afterId,
                    limit = MANIFEST_VERIFY_PAGE_SIZE
                )
            }
            startupTrace.cost(StartupCostStage.MANIFEST_DIGEST, domain) {
                for (row in rows) {
                    checkOrdinaryNoWorkReceiptPriority(db, "canonical_row")
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
            }
            checkOrdinaryNoWorkReceiptPriority(db, "canonical_page_end")
            afterId = rows.lastOrNull()?.remoteId
        } while (rows.size == MANIFEST_VERIFY_PAGE_SIZE)
        startupTrace.cost(StartupCostStage.MANIFEST_DIGEST, domain) {
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
    checkOrdinaryNoWorkReceiptPriority(db, "relational_begin")
    var image = images.next()
    while (true) {
        checkOrdinaryNoWorkReceiptPriority(db, "relational_row")
        val product = products.next() ?: break
        if (image != null && image.remoteId < product.remoteId) {
            throw ShopSyncContractException("recovery_image_product_invalid")
        }
        val productParts = productManifestParts(product)
        val primaryImage = productParts[5].takeUnless { it == "-" }?.let(::canonicalUuid)
        if (primaryImage == null) {
            if (image?.remoteId == product.remoteId) {
                // V6 deliberately keeps an image-domain tombstone when a
                // deleted product used to have a primary image. It is
                // evidence for the image digest, never a live relation.
                if (
                    !product.active &&
                    !image.active &&
                    productDeletedAt(productParts) == imageProductDeletedAt(image)
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
    val startupTrace = coroutineContext[LocalStartupTrace]
    checkOrdinaryNoWorkReceiptPriority(db, "physical_begin")
    val resolvedTombstones = if (localOverlay) validateBusinessResolutionPhysical(db).tombstoneRemoteIds else emptySet()
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
            checkOrdinaryNoWorkReceiptPriority(db, "physical_page")
            val expected = startupTrace.cost(StartupCostStage.PHYSICAL_EXPECTED, domain) {
                if (localAcknowledgements) {
                    localAcknowledgedManifestPage(db, generationId, domain, afterId, pageSize, resolvedTombstones)
                } else if (localOverlay) {
                    cleanOverlayManifestPage(db, generationId, domain, afterId, pageSize, resolvedTombstones)
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
            }
            // Only an exact-domain, same-ID ACK consumes this secondary fingerprint. The set is
            // bounded by the already-read expected page; canonical bodies remain fully verified.
            val acknowledgedRemoteIds = if (localAcknowledgements && domain != ShopSyncRowDomain.HISTORY) {
                expected.asSequence()
                    .filter { it.domain == LOCAL_ACK_BODY_PREFIX + domain.wireValue }
                    .map { it.remoteId }
                    .toSet()
            } else emptySet()
            checkOrdinaryNoWorkReceiptPriority(db, "physical_read_begin")
            val physical = readPhysicalPage(db, generationId, domain, afterId, pageSize, activeStoreHistory,
                localOverlay, localAcknowledgements, acknowledgedRemoteIds)
            checkOrdinaryNoWorkReceiptPriority(db, "physical_read_end")
            if (physical.size != expected.size) {
                throw ShopSyncContractException(
                    "recovery_physical_count_mismatch_${domain.wireValue}"
                )
            }
            startupTrace.cost(StartupCostStage.PHYSICAL_COMPARE, domain) {
                expected.zip(physical).forEach body@ { (manifest, materialized) ->
                    checkOrdinaryNoWorkReceiptPriority(db, "physical_compare_row")
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
    db: AppDatabase, generationId: String, domain: ShopSyncRowDomain, afterId: String?, limit: Int,
    resolvedTombstones: Set<String> = emptySet()
): List<SyncRecoveryManifestRow> {
    val result = ArrayList<SyncRecoveryManifestRow>(limit)
    var cursor = afterId
    while (result.size < limit) {
        coroutineContext.ensureActive()
        checkOrdinaryNoWorkReceiptPriority(db, "overlay_or_ack_page")
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
        checkOrdinaryNoWorkReceiptPriority(db, "overlay_query_end")
        result += rows.filter { it.remoteId !in exempt &&
            (domain != ShopSyncRowDomain.PRODUCTS || it.remoteId !in resolvedTombstones) }
        cursor = rows.last().remoteId
        if (rows.size < batchLimit) break
    }
    return result
}

/** Merge two bounded keyset pages; actual ACK proofs replace only their same-ID historical body. */
private suspend fun localAcknowledgedManifestPage(
    db: AppDatabase, generationId: String, domain: ShopSyncRowDomain, afterId: String?, limit: Int,
    resolvedTombstones: Set<String> = emptySet()
): List<SyncRecoveryManifestRow> {
    val result = ArrayList<SyncRecoveryManifestRow>(limit)
    var cursor = afterId
    while (result.size < limit) {
        coroutineContext.ensureActive()
        checkOrdinaryNoWorkReceiptPriority(db, "overlay_or_ack_page")
        val bound = limit - result.size
        val canonical = if (domain == ShopSyncRowDomain.PRICES)
            materializablePriceManifestPage(db, generationId, cursor, bound)
            else db.syncRecoveryManifestDao().pageActive(generationId,domain.wireValue,cursor,bound)
        checkOrdinaryNoWorkReceiptPriority(db, "ack_canonical_page_end")
        val acknowledged = db.syncRecoveryManifestDao().pageActive(
            generationId,LOCAL_ACK_BODY_PREFIX+domain.wireValue,cursor,bound)
        checkOrdinaryNoWorkReceiptPriority(db, "ack_page_end")
        val merged = (canonical + acknowledged).associateBy { it.remoteId }.values.sortedBy { it.remoteId }.take(bound)
        if (merged.isEmpty()) break
        val exempt = db.openHelper.readableDatabase.query(
            "SELECT m.remoteId FROM sync_recovery_manifest m WHERE m.generationId=? AND m.domain IN (?,?) " +
                "AND m.remoteId IN (${merged.joinToString(",") { "?" }}) AND (${overlayPendingPredicate(domain, "m.remoteId", "m.generationId")})",
            (listOf(generationId,domain.wireValue,LOCAL_ACK_BODY_PREFIX+domain.wireValue)+merged.map { it.remoteId }).toTypedArray()
        ).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
        checkOrdinaryNoWorkReceiptPriority(db, "ack_query_end")
        result += merged.filter { it.remoteId !in exempt &&
            (domain != ShopSyncRowDomain.PRODUCTS || it.remoteId !in resolvedTombstones) }
        cursor=merged.last().remoteId
        if (merged.size<bound) break
    }
    return result
}

private fun validateLocalAcknowledgedMaterialization(
    binding: BusinessDataScopeBinding, device: String, proof: SyncRecoveryManifestRow, physical: PhysicalRecoveryRow
) {
    validateLocalAcknowledgedProof(binding, device, proof, physical.remoteId,
        physical.acknowledgedRevision, physical.localAckFingerprint)
}

private fun validateLocalAcknowledgedProof(
    binding: BusinessDataScopeBinding, device: String, proof: SyncRecoveryManifestRow,
    remoteId: String, acknowledgedRevision: Long?, acknowledgedFingerprint: String?
) {
    if (proof.versionLine.length>4096 || proof.idLine!=proof.remoteId || proof.remoteId!=remoteId ||
        proof.payloadDigest==null || proof.payloadDigest!=acknowledgedFingerprint)
        throw ShopSyncContractException("local_ack_physical_body_mismatch")
    val identity=try { Json.decodeFromString<LocalAcknowledgedBodyIdentity>(proof.versionLine) }
        catch (_: Exception) { throw ShopSyncContractException("local_ack_identity_invalid") }
    if(!identity.matches(binding,device) || identity.revision!=acknowledgedRevision)
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

/** A projection hint, never a validity cache. The original Room transaction stays owned. */
private class PriceParentProjectionContext(
    val database: AppDatabase, val generationId: String
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PriceParentProjectionContext>
}

internal suspend fun <T> withPriceParentProjection(
    db: AppDatabase, generationId: String, block: suspend () -> T
): T = if (!db.inTransaction()) block() else withContext(PriceParentProjectionContext(db, generationId)) { block() }

private data class PriceParentProjection(val remoteId: String, val active: Boolean, val versionLine: String)

/** Called only on the current original transaction thread; never opens another dispatcher/transaction. */
private fun readPriceParentProjection(
    db: AppDatabase, generationId: String, parentIds: List<String>
): Map<String, PriceParentProjection> {
    val placeholders = parentIds.joinToString(",") { "?" }
    val args = (listOf<Any>(generationId) + parentIds).toTypedArray()
    return db.openHelper.readableDatabase.query(
        "SELECT remoteId, active, versionLine FROM sync_recovery_manifest " +
            "WHERE generationId = ? AND domain = 'products' AND remoteId IN ($placeholders)", args
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val remoteId = cursor.getString(0)
                put(remoteId, PriceParentProjection(remoteId, cursor.getInt(1) != 0, cursor.getString(2)))
            }
        }
    }
}

private suspend inline fun <T> validatedActivePriceParents(
    db: AppDatabase, parentIds: List<String>, parents: Map<String, T>,
    active: (T) -> Boolean, deletedAt: (T) -> String?
): Set<String> = parentIds.filterTo(hashSetOf()) { parentId ->
    checkOrdinaryNoWorkReceiptPriority(db, "price_parent_row")
    val parent = parents[parentId]
        ?: throw ShopSyncContractException("recovery_price_parent_manifest_missing")
    if (active(parent) != (deletedAt(parent) == null)) {
        throw ShopSyncContractException("recovery_price_parent_manifest_invalid")
    }
    active(parent)
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
    checkOrdinaryNoWorkReceiptPriority(db, "price_parents_begin")
    val startupTrace = coroutineContext[LocalStartupTrace]
    val projection = coroutineContext[PriceParentProjectionContext]
    if (projection != null && projection.database === db && projection.generationId == generationId && db.inTransaction()) {
        val parents = startupTrace.cost(StartupCostStage.PRICE_PARENT_FETCH, ShopSyncRowDomain.PRICES) {
            readPriceParentProjection(db, generationId, parentIds)
        }
        return startupTrace.cost(StartupCostStage.PRICE_PARENT_VALIDATE, ShopSyncRowDomain.PRICES) {
            validatedActivePriceParents(db, parentIds, parents, { it.active }, {
                productDeletedAt(it.remoteId, it.versionLine)
            })
        }
    }
    // Other recovery callers keep the original suspending DAO/Room connection dispatch.
    val parents = startupTrace.cost(StartupCostStage.PRICE_PARENT_FETCH, ShopSyncRowDomain.PRICES) {
        db.syncRecoveryManifestDao()
            .getProductsByRemoteIds(generationId, parentIds)
            .associateBy { it.remoteId }
    }
    return startupTrace.cost(StartupCostStage.PRICE_PARENT_VALIDATE, ShopSyncRowDomain.PRICES) {
        validatedActivePriceParents(db, parentIds, parents, { it.active }, ::productDeletedAt)
    }
}

private fun priceManifestParentId(row: SyncRecoveryManifestRow): String {
    val line = row.versionLine
    var separator = -1
    var first = -1
    var second = -1
    var third = -1
    repeat(8) { index ->
        separator = line.indexOf('\u001f', separator + 1)
        if (separator < 0) throw ShopSyncContractException("recovery_price_parent_manifest_invalid")
        when (index) { 0 -> first = separator; 1 -> second = separator; 2 -> third = separator }
    }
    // The original unrestricted split requires exactly nine fields, including an
    // empty trailing field. A ninth separator must still reject before UUID work.
    if (line.indexOf('\u001f', separator + 1) >= 0 || first != row.remoteId.length ||
        !line.regionMatches(0, row.remoteId, 0, row.remoteId.length)) {
        throw ShopSyncContractException("recovery_price_parent_manifest_invalid")
    }
    val parentText = line.substring(second + 1, third)
    val parentId = canonicalRecoveryEntityUuid(parentText)
    if (parentId != parentText) {
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
    val startupTrace = coroutineContext[LocalStartupTrace]
    val materializable = ArrayList<SyncRecoveryManifestRow>(limit)
    var cursor = afterId
    while (materializable.size < limit) {
        coroutineContext.ensureActive()
        checkOrdinaryNoWorkReceiptPriority(db, "price_page")
        val batchLimit = limit - materializable.size
        val rows = startupTrace.cost(StartupCostStage.PRICE_PAGE_FETCH, ShopSyncRowDomain.PRICES) {
            db.syncRecoveryManifestDao().pageActive(
                generationId,
                ShopSyncRowDomain.PRICES.wireValue,
                cursor,
                batchLimit
            )
        }
        checkOrdinaryNoWorkReceiptPriority(db, "price_page_end")
        if (rows.isEmpty()) break
        val parentIds = startupTrace.cost(StartupCostStage.PRICE_PARENT_VALIDATE, ShopSyncRowDomain.PRICES) { rows.map(::priceManifestParentId) }
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
        checkOrdinaryNoWorkReceiptPriority(db, "price_count_page")
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
    includeLocalAckFingerprints: Boolean = false,
    acknowledgedRemoteIds: Set<String>
): List<PhysicalRecoveryRow> {
    val startupTrace = coroutineContext[LocalStartupTrace]
    if (domain == ShopSyncRowDomain.HISTORY) {
        val rows = startupTrace.cost(StartupCostStage.PHYSICAL_HISTORY_FETCH, domain) {
            if (activeStoreHistory) {
                db.historyEntryDao().getRecoveryActivePhysicalPage(afterId, limit, onlyClean = localOverlay)
            } else {
                db.historyEntryDao().getRecoveryPhysicalPage(afterId, limit, onlyClean = localOverlay)
            }
        }
        return startupTrace.cost(StartupCostStage.PHYSICAL_PROJECTION, domain) {
            rows.map { row ->
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
    checkOrdinaryNoWorkReceiptPriority(db, "physical_sql_begin")
    return startupTrace.cost(StartupCostStage.PHYSICAL_QUERY_OPEN, domain) {
        db.openHelper.readableDatabase.query(scopedSql, args)
    }.use { cursor ->
        buildList {
            while (startupTrace.cost(StartupCostStage.PHYSICAL_CURSOR_MOVE, domain) { cursor.moveToNext() }) {
                checkOrdinaryNoWorkReceiptPriority(db, "physical_sql_row")
                add(startupTrace.cost(StartupCostStage.PHYSICAL_PROJECTION, domain) {
                    physicalRow(domain, cursor, includeLocalAckFingerprints, acknowledgedRemoteIds)
                })
            }
        }
    }
}

private fun physicalRow(
    domain: ShopSyncRowDomain,
    cursor: Cursor,
    includeLocalAckFingerprints: Boolean,
    acknowledgedRemoteIds: Set<String>
): PhysicalRecoveryRow =
    when (domain) {
        ShopSyncRowDomain.SUPPLIERS -> {
            requireCleanPhysicalRef(cursor, 3, 4)
            val remoteId = canonicalRecoveryEntityUuid(cursor.getString(0))
            val updatedAt = canonicalTimestamp(cursor.nullableString(2))
            PhysicalRecoveryRow(
                remoteId = remoteId,
                payloadDigest = sha256(recoverySupplierPayloadFingerprint(cursor.getString(1))),
                versionLine = listOf(remoteId, updatedAt, "-").joinToString("\u001f"),
                localAckFingerprint = if (includeLocalAckFingerprints) InventorySupplierRow(
                    id=remoteId,ownerUserId="",name=cursor.getString(1),updatedAt=cursor.nullableString(2)).let { row ->
                    if (remoteId in acknowledgedRemoteIds) fingerprintSupplierInbound(row)
                    else {
                        CatalogTextCanonicalizer.supplierName(row.name)
                        null
                    }
                } else null,
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
                localAckFingerprint = if (includeLocalAckFingerprints) InventoryCategoryRow(
                    id=remoteId,ownerUserId="",name=cursor.getString(1),updatedAt=cursor.nullableString(2)).let { row ->
                    if (remoteId in acknowledgedRemoteIds) fingerprintCategoryInbound(row)
                    else {
                        CatalogTextCanonicalizer.categoryName(row.name)
                        null
                    }
                } else null,
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
                localAckFingerprint = if (includeLocalAckFingerprints) InventoryProductRow(
                    id=remoteId,ownerUserId="",barcode=cursor.getString(1).trim(),
                    itemNumber=cursor.nullableString(2),productName=cursor.nullableString(3),
                    secondProductName=cursor.nullableString(4),purchasePrice=cursor.nullableDouble(5),
                    retailPrice=cursor.nullableDouble(6),stockQuantity=cursor.nullableDouble(7),
                    primaryImageVersionId=imageId,primaryImageUpdatedAt=imageUpdatedAt,
                    supplierId=supplierId,categoryId=categoryId,updatedAt=cursor.nullableString(12)).let { row ->
                    if (remoteId in acknowledgedRemoteIds) fingerprintProductInbound(row)
                    else {
                        CatalogTextCanonicalizer.barcode(row.barcode)
                        row.itemNumber?.let(CatalogTextCanonicalizer::itemNumber)
                        row.productName?.let { CatalogTextCanonicalizer.productName(it) }
                        row.secondProductName?.let { CatalogTextCanonicalizer.secondProductName(it) }
                        CatalogTextCanonicalizer.optionalRemoteId(row.supplierId)
                        CatalogTextCanonicalizer.optionalRemoteId(row.categoryId)
                        CatalogTextCanonicalizer.optionalRemoteId(row.primaryImageVersionId)
                        null
                    }
                } else null,
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
                localAckFingerprint = if (includeLocalAckFingerprints && remoteId in acknowledgedRemoteIds)
                    localAcknowledgedPriceFingerprint(InventoryProductPriceRow(
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
    localAcknowledgements: Boolean = false,
    localOverlay: Boolean = false
) {
    val sql = stagingDb.openHelper.readableDatabase
    checkOrdinaryNoWorkReceiptPriority(stagingDb, "integrity_sql_begin")
    requirePragmaOk(sql, "PRAGMA integrity_check")
    checkOrdinaryNoWorkReceiptPriority(stagingDb, "integrity_sql_end")
    checkOrdinaryNoWorkReceiptPriority(stagingDb, "foreign_key_sql_begin")
    sql.query("PRAGMA foreign_key_check").use { cursor ->
        if (cursor.moveToFirst()) {
            throw ShopSyncContractException("recovery_staging_foreign_key_violation")
        }
    }
    checkOrdinaryNoWorkReceiptPriority(stagingDb, "foreign_key_sql_end")
    val materializedPriceCount = materializablePriceCount(stagingDb, generationId)
    val historyShadowCount = if (activeStoreHistory) {
        checkOrdinaryNoWorkReceiptPriority(stagingDb, "history_count_sql_begin")
        sql.query("SELECT COUNT(*) FROM history_entries WHERE deletedAt IS NOT NULL").use {
            check(it.moveToFirst())
            it.getLong(0)
        }
    } else 0L
    checkOrdinaryNoWorkReceiptPriority(stagingDb, "history_count_sql_end")
    val resolvedTombstones = if (localOverlay)
        validateBusinessResolutionPhysical(stagingDb).tombstoneRemoteIds else emptySet()
    // A is immutable; a real same-generation ACK may already add a clean C body.
    // Count the same bounded canonical+ACK keyset used by the strict body proof below.
    suspend fun expectedCount(domain: ShopSyncRowDomain, canonicalCount: Long): Long {
        if (!localAcknowledgements) return canonicalCount
        var count = 0L
        var afterId: String? = null
        do {
            checkOrdinaryNoWorkReceiptPriority(stagingDb, "expected_count_page")
            val rows = localAcknowledgedManifestPage(stagingDb, generationId, domain, afterId, PHYSICAL_VERIFY_PAGE_SIZE, resolvedTombstones)
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
    val pendingCounts = if (localOverlay) {
        check(activeStoreHistory && localAcknowledgements) { "recovery_active_partition_mode_invalid" }
        activePendingTableCounts(stagingDb, generationId, checkpoint)
    } else emptyMap()
    expectedCounts.forEach { (table, expected) ->
        checkOrdinaryNoWorkReceiptPriority(stagingDb, "table_count_sql_begin")
        if (queryCount(sql, table) != Math.addExact(expected, pendingCounts[table] ?: 0L)) {
            throw ShopSyncContractException("recovery_staging_table_count_mismatch_$table")
        }
        checkOrdinaryNoWorkReceiptPriority(stagingDb, "table_count_sql_end")
    }
}


/** Pending is an existing owned overlay, not a canonical row or an invented ACK. */
private suspend fun activePendingTableCounts(
    db: AppDatabase, generationId: String, checkpoint: ShopSyncRecoveryCheckpoint
): Map<String, Long> {
    val resolutionProof = validateBusinessResolutionPhysical(db)
    val binding = db.businessDataScopeBindingDao().get()
        ?: throw ShopSyncContractException("recovery_active_partition_scope_missing")
    val device = db.syncEventDeviceStateDao().get()?.deviceId
        ?: throw ShopSyncContractException("recovery_active_partition_scope_missing")
    val baseline = db.syncRecoveryBaselineDao().get()
        ?: throw ShopSyncContractException("recovery_active_partition_scope_missing")
    // Ordinary publication proves C while the captured baseline A is still persisted.
    // The caller rechecks A before publishing C; only identity/scope must match here.
    if (baseline.generationId != generationId || baseline.ownerHash != binding.ownerHash ||
        baseline.storeScope != binding.storeId || baseline.deviceId != device ||
        baseline.scopeKind != checkpoint.scope.kind || baseline.scopeKey != checkpoint.scope.key) {
        throw ShopSyncContractException("recovery_active_partition_scope_mismatch")
    }
    val sql = db.openHelper.readableDatabase
    val counts = mutableMapOf<String, Long>()
    // SameScopeRecoveryOverlay.capture already preserves unbridged bodies (including lazy prices/history).
    val domains = listOf(
        Triple(ShopSyncRowDomain.SUPPLIERS, "suppliers", "supplierId"),
        Triple(ShopSyncRowDomain.CATEGORIES, "categories", "categoryId"),
        Triple(ShopSyncRowDomain.PRODUCTS, "products", "productId"),
        Triple(ShopSyncRowDomain.PRICES, "product_prices", "productPriceId"),
        Triple(ShopSyncRowDomain.HISTORY, "history_entries", "historyEntryUid")
    )
    for ((domain, table, foreign) in domains) {
        val refs = when (domain) {
            ShopSyncRowDomain.SUPPLIERS -> "supplier_remote_refs"
            ShopSyncRowDomain.CATEGORIES -> "category_remote_refs"
            ShopSyncRowDomain.PRODUCTS -> "product_remote_refs"
            ShopSyncRowDomain.PRICES -> "product_price_remote_refs"
            else -> "history_entry_remote_refs"
        }
        val key = if (domain == ShopSyncRowDomain.HISTORY) "uid" else "id"
        checkOrdinaryNoWorkReceiptPriority(db, "active_partition_shape")
        sql.query(
            "SELECT 1 FROM $refs r LEFT JOIN $table p ON p.$key=r.$foreign WHERE p.$key IS NULL " +
                "UNION ALL SELECT 1 FROM $refs GROUP BY $foreign HAVING COUNT(*)<>1 " +
                "UNION ALL SELECT 1 FROM $refs GROUP BY remoteId HAVING COUNT(*)<>1 LIMIT 1"
        ).use { c ->
            if (c.moveToFirst()) throw ShopSyncContractException("recovery_active_partition_ref_invalid_$table")
        }
        val entity = when (domain) {
            ShopSyncRowDomain.SUPPLIERS -> "SUPPLIER"
            ShopSyncRowDomain.CATEGORIES -> "CATEGORY"
            ShopSyncRowDomain.PRODUCTS -> "PRODUCT"
            else -> null
        }
        if (entity != null) sql.query(
            "SELECT 1 FROM $table p JOIN $refs r ON r.$foreign=p.$key " +
                "JOIN pending_catalog_tombstones t ON t.remoteId=r.remoteId WHERE t.entityType=? LIMIT 1",
            arrayOf(entity)
        ).use { c ->
            if (c.moveToFirst()) throw ShopSyncContractException("recovery_active_partition_tombstone_body_$table")
        }
        val isPrice = domain == ShopSyncRowDomain.PRICES
        val historyActive = if (domain == ShopSyncRowDomain.HISTORY) " AND p.deletedAt IS NULL" else ""
        val dirty = if (isPrice) "r.id IS NULL" else
            "r.id IS NULL OR r.lastRemoteAppliedAt IS NULL OR r.localChangeRevision<>r.lastSyncedLocalRevision" +
                if (domain == ShopSyncRowDomain.HISTORY) " OR p.syncStatus<>'SYNCED_SUCCESSFULLY'" else ""
        val metadata = if (isPrice) "NULL, NULL, NULL, NULL" else
            "r.localChangeRevision, r.lastSyncedLocalRevision, r.lastRemoteAppliedAt, r.lastRemotePayloadFingerprint"
        var afterId: Long? = null
        var bodyCount = 0L
        var refCount = 0L
        do {
            coroutineContext.ensureActive()
            checkOrdinaryNoWorkReceiptPriority(db, "active_partition_page")
            val rows = sql.query(
                "SELECT p.$key, r.remoteId, $metadata FROM $table p LEFT JOIN $refs r ON r.$foreign=p.$key " +
                    "WHERE ($dirty)$historyActive AND (? IS NULL OR p.$key>?) ORDER BY p.$key LIMIT ?",
                arrayOf<Any?>(afterId, afterId, PHYSICAL_VERIFY_PAGE_SIZE)
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        checkOrdinaryNoWorkReceiptPriority(db, "active_partition_row")
                        val remoteId = c.nullableString(1)
                        if (remoteId != null) {
                            if (canonicalRecoveryEntityUuid(remoteId) != remoteId || c.isNull(2) || c.isNull(3) ||
                                c.getLong(2) !in 0..Int.MAX_VALUE.toLong() || c.getLong(3) !in 0..c.getLong(2) ||
                                (c.isNull(4) && c.getLong(3) != 0L) || (!c.isNull(4) && c.getLong(4) < 0L)) {
                                throw ShopSyncContractException("recovery_active_partition_revision_invalid_$table")
                            }
                        }
                        add(Triple(c.getLong(0), remoteId,
                            if (remoteId == null) null else c.getLong(3) to c.nullableString(5)))
                    }
                }
            }
            for ((_, remoteId, acknowledged) in rows) {
                bodyCount = Math.addExact(bodyCount, 1L)
                if (remoteId != null) {
                    refCount = Math.addExact(refCount, 1L)
                    val proof = db.syncRecoveryManifestDao().get(
                        generationId, LOCAL_ACK_BODY_PREFIX + domain.wireValue, remoteId)
                    if (proof != null) {
                        if (!proof.active) throw ShopSyncContractException("local_ack_identity_invalid")
                        // Compare the saved ACK witness, never the subsequently edited pending body.
                        val (revision, fingerprint) = requireNotNull(acknowledged)
                        val acknowledgedFingerprint = if (domain == ShopSyncRowDomain.PRODUCTS &&
                            resolutionProof.acknowledgedFingerprints.containsKey(remoteId)) {
                            resolutionProof.acknowledgedFingerprints[remoteId]
                        } else fingerprint
                        validateLocalAcknowledgedProof(binding, device, proof, remoteId, revision, acknowledgedFingerprint)
                    }
                }
            }
            afterId = rows.lastOrNull()?.first ?: afterId
        } while (rows.size == PHYSICAL_VERIFY_PAGE_SIZE)
        counts[table] = bodyCount
        counts[refs] = refCount
    }
    return counts
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

private fun productDeletedAt(parts: List<String>): String? =
    parts[2].takeUnless { it == "-" }?.let(::canonicalTimestamp)

private fun productDeletedAt(row: SyncRecoveryManifestRow): String? =
    productDeletedAt(row.remoteId, row.versionLine)

/** Exactly the old split(separator, limit=7) shape/id checks, without allocating unused fields. */
private fun productDeletedAt(remoteId: String, versionLine: String): String? {
    var separator = -1
    var first = -1
    var second = -1
    var third = -1
    repeat(6) { index ->
        separator = versionLine.indexOf('\u001f', separator + 1)
        if (separator < 0) throw ShopSyncContractException("recovery_product_manifest_invalid")
        when (index) { 0 -> first = separator; 1 -> second = separator; 2 -> third = separator }
    }
    // limit=7 deliberately allows additional separators in the last, unconsumed field.
    if (first != remoteId.length || !versionLine.regionMatches(0, remoteId, 0, remoteId.length)) {
        throw ShopSyncContractException("recovery_product_manifest_invalid")
    }
    val start = second + 1
    return if (third == start + 1 && versionLine[start] == '-') null
        else canonicalTimestamp(versionLine.substring(start, third))
}

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
    // Reduce per-row SQLite executions without changing the writer boundary or storage model.
    // A single oversized row keeps the previous single-row memory bound; rows are never split.
    val maximumRows = minOf(64, 999 / spec.columns.size)
    check(maximumRows > 0)
    val maximumBatchBytes = 1024L * 1024L
    val batch = ArrayList<Array<Any?>>(maximumRows)
    var batchBytes = 0L
    var statementRows = 0
    var statement: SupportSQLiteStatement? = null
    fun flushBatch() {
        if (batch.isEmpty()) return
        if (statementRows != batch.size) {
            val previousStatement = statement
            statement = null
            previousStatement?.close()
            statement = destination.compileStatement("INSERT INTO `$target` ($columns) VALUES " +
                List(batch.size) { "($placeholders)" }.joinToString(","))
            statementRows = batch.size
        }
        val insert = requireNotNull(statement)
        insert.clearBindings()
        batch.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, value ->
                val position = rowIndex * spec.columns.size + columnIndex + 1
                when (value) {
                    null -> insert.bindNull(position)
                    is Long -> insert.bindLong(position, value)
                    is Double -> insert.bindDouble(position, value)
                    is String -> insert.bindString(position, value)
                    is ByteArray -> insert.bindBlob(position, value)
                    else -> throw ShopSyncContractException("recovery_cursor_type_unsupported")
                }
            }
        }
        insert.executeInsert()
        insert.clearBindings()
        batch.clear()
        batchBytes = 0L
    }
    try {
        source.query("SELECT $columns FROM `${spec.table}`").use { cursor ->
            while (cursor.moveToNext()) {
                val row = Array(spec.columns.size) { index -> cursor.valueAt(index) }
                val rowBytes = row.sumOf { value ->
                    when (value) {
                        is String -> value.length.toLong() * 2L
                        is ByteArray -> value.size.toLong()
                        else -> 8L
                    }
                }
                if (batch.isNotEmpty() && batchBytes + rowBytes > maximumBatchBytes) flushBatch()
                batch.add(row)
                batchBytes += rowBytes
                copied++
                if (batch.size == maximumRows || batchBytes >= maximumBatchBytes) flushBatch()
            }
        }
        flushBatch()
    } finally {
        statement?.close()
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
        val payload = line.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$state\u001f${payload.size}:".toByteArray(Charsets.UTF_8))
        state = encodeLowercaseHex(digest.digest(payload))
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

private fun sha256(value: String): String =
    encodeLowercaseHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

private fun encodeLowercaseHex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val encoded = CharArray(bytes.size * 2)
    for (index in bytes.indices) {
        val unsigned = bytes[index].toInt() and 0xff
        encoded[index * 2] = digits[unsigned ushr 4]
        encoded[index * 2 + 1] = digits[unsigned and 0x0f]
    }
    return String(encoded)
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


/**
 * Optional SAME_SCOPE publication optimization. Must be called before baseline deletion,
 * inside the existing activation transaction after its scope/device/journal CAS checks.
 * False means no manifest DML occurred and the original DELETE + INSERT must run.
 * The body proof includes every domain (including local ACK), not only remote business.
 */
private fun advanceEqualRecoveryManifestGeneration(
    sql: SupportSQLiteDatabase,
    capturedPreviousGeneration: String?,
    newGeneration: String
): Boolean {
    check(sql.inTransaction()) { "Manifest publication requires the activation transaction" }
    if (capturedPreviousGeneration.isNullOrBlank() || newGeneration.isBlank() ||
        capturedPreviousGeneration == newGeneration) return false
    val active = "main.sync_recovery_manifest"
    val staged = "temp.mc_recovery_source_sync_recovery_manifest"
    fun hasRow(query: String, args: Array<out Any?> = emptyArray()): Boolean =
        sql.query(query, args).use { it.moveToFirst() }
    fun baselineMatches(table: String, expected: String): Boolean =
        sql.query("SELECT id,generationId FROM $table LIMIT 2").use { c ->
            c.moveToFirst() && c.getLong(0) == SyncRecoveryBaseline.SINGLETON_ID.toLong() &&
                !c.isNull(1) && c.getString(1) == expected && !c.moveToNext()
        }
    fun generationAndKeysMatch(table: String, expected: String): Boolean =
        hasRow("SELECT 1 FROM $table LIMIT 1") &&
            !hasRow("SELECT 1 FROM $table WHERE generationId COLLATE BINARY IS NOT ? COLLATE BINARY LIMIT 1",
                arrayOf(expected)) &&
            !hasRow("SELECT 1 FROM $table GROUP BY domain COLLATE BINARY, remoteId COLLATE BINARY " +
                "HAVING COUNT(*) <> 1 LIMIT 1")
    if (!baselineMatches("main.sync_recovery_baseline", capturedPreviousGeneration) ||
        !baselineMatches("temp.mc_recovery_source_sync_recovery_baseline", newGeneration) ||
        !generationAndKeysMatch(active, capturedPreviousGeneration) ||
        !generationAndKeysMatch(staged, newGeneration)) return false

    fun columns(schema: String, table: String): List<String> =
        sql.query("PRAGMA $schema.table_info(`$table`)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1)) }
        }
    val activeColumns = columns("main", "sync_recovery_manifest")
    if (activeColumns != columns("temp", "mc_recovery_source_sync_recovery_manifest") ||
        !activeColumns.containsAll(listOf("generationId", "domain", "remoteId", "active", "idLine",
            "versionLine", "identityLine", "payloadDigest"))) return false
    val body = activeColumns.filterNot { it == "generationId" }
        .joinToString(",") { "`$it` COLLATE BINARY" }
    // EXCEPT compares NULL with NULL as equal (the same null-safe value relation as IS).
    // Explicit BINARY is applied on both sides; uniqueness above prevents set comparison
    // from hiding duplicate rows. Two directions reject missing and extra identities.
    // This avoids a correlated reverse scan over the existing unindexed TEMP manifest.
    if (hasRow("SELECT 1 FROM (SELECT $body FROM $active EXCEPT SELECT $body FROM $staged) LIMIT 1") ||
        hasRow("SELECT 1 FROM (SELECT $body FROM $staged EXCEPT SELECT $body FROM $active) LIMIT 1")) return false

    sql.compileStatement("UPDATE sync_recovery_manifest SET generationId=? WHERE generationId=?").use { update ->
        update.bindString(1, newGeneration)
        update.bindString(2, capturedPreviousGeneration)
        check(update.executeUpdateDelete() > 0) { "Qualified manifest update changed no rows" }
    }
    return true
}
