package com.example.merchandisecontrolsplitview.data

import android.app.Application
import android.os.Process
import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Typed additions for the existing owned process/marker host. No new runner or scheduler. */
internal object NativeAutomaticRecoveryContract {
    const val PROTOCOL = "task144-native-automatic-recovery-v1"
    const val CLASS = "com.example.merchandisecontrolsplitview.ui.navigation.Task144AutomaticRecoveryProcessDeviceTest"
    const val SAVED_NAME = "T10a saved during accepted staging"
    const val SAVED_RETAIL = 8.0
    val scenarios = setOf("automatic-prefix", "abandoned-claim", "save-before-activation")
    // selector -> (scenario, fresh store). Each process is separately admitted by the existing host.
    val methods = mapOf(
        "prepareAutomaticAcceptedPrefix" to ("automatic-prefix" to true),
        "verifyAutomaticPrefixAfterProcessDeath" to ("automatic-prefix" to false),
        "prepareAbandonedAutomaticClaim" to ("abandoned-claim" to true),
        "prepareRequalificationDeadlineAfterProcessDeath" to ("abandoned-claim" to false),
        "verifySecondReopenKeepsDeadlineAndRecovers" to ("abandoned-claim" to false),
        "prepareEditorSaveBeforeActivation" to ("save-before-activation" to true),
        "prepareSavedRecoveryAfterFirstReopen" to ("save-before-activation" to false),
        "verifySavedRecoveryAfterSecondReopen" to ("save-before-activation" to false)
    )
}

internal fun automaticRecoveryContext(method: String): ScopeSwitchContext {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(instrumentation.javaClass.name == MobileSyncPerformanceTestRunner::class.java.name)
    check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    val args = InstrumentationRegistry.getArguments()
    val (scenario, fresh) = NativeAutomaticRecoveryContract.methods.getValue(method)
    check(args.getString("class") == NativeAutomaticRecoveryContract.CLASS + "#" + method)
    check(args.getString("nativeAutomaticRecoveryProtocol") == NativeAutomaticRecoveryContract.PROTOCOL)
    return ScopeSwitchContext(instrumentation.targetContext, requireNotNull(args.getString("nativeAutomaticRecoveryRunId")),
        scenario, fresh, NativeAutomaticRecoveryContract.PROTOCOL)
}

internal enum class NativeAutomaticHold { NONE, BEFORE_PRICES, BEFORE_CHECKPOINT_B }

/** Transparent test reader wrapper. It records the actual persisted claim at RPC entry and may
 * hold one existing request. No response, retry metadata, clock or production authority is created. */
internal class NativeAutomaticRecoveryProbe(private val context: ScopeSwitchContext, private val hold: NativeAutomaticHold) {
    private lateinit var fixture: ScopeSwitchFixture
    private val armed = AtomicBoolean(false)
    private val held = AtomicBoolean(false)
    private val checkpoints = AtomicInteger(0)
    private val release = CompletableDeferred<Unit>()
    private val file = File(context.root, "automatic-reader.jsonl")
    fun attach(value: ScopeSwitchFixture) { check(!::fixture.isInitialized); fixture = value }
    fun arm() { check(armed.compareAndSet(false, true)) }
    fun isHeld() = held.get()
    fun releaseForFailedCleanup() { release.complete(Unit) }
    @Synchronized fun rows(): List<JsonObject> = if (!file.isFile) emptyList() else file.readLines()
        .filter { it.isNotBlank() }.map { NativeResolutionContract.json.parseToJsonElement(it).jsonObject }
    @Synchronized private fun record(value: JsonObject) {
        FileOutputStream(file, true).use { it.write((value.toString() + "\n").encodeToByteArray()); it.fd.sync() }
    }
    fun hash(): String? = file.takeIf { it.isFile }?.readBytes()?.let(::nativeSha)
    private suspend fun enter(method: String, rpc: ShopSyncRpcContext, domain: ShopSyncRowDomain? = null,
        afterId: String? = null, limit: Int? = null) {
        if (!armed.get()) return
        val journal = fixture.db.syncRecoveryJournalDao().get() ?: return // Ordinary post-recovery reads pass through unchanged.
        val retry = requireNotNull(AutomaticRecoveryRetryMetadata.decode(journal.reason))
        check(retry.state == "in_flight" && retry.processEpoch != null)
        val shouldHold = (method == "checkpoint" && checkpoints.incrementAndGet() == 2 && hold == NativeAutomaticHold.BEFORE_CHECKPOINT_B) ||
            (method == "recoveryPage" && domain == ShopSyncRowDomain.PRICES && hold == NativeAutomaticHold.BEFORE_PRICES)
        record(buildJsonObject {
            put("pid", Process.myPid()); put("elapsedNs", SystemClock.elapsedRealtimeNanos()); put("wallMs", System.currentTimeMillis())
            put("method", method); put("domain", domain?.wireValue); put("afterId", afterId); put("limit", limit)
            put("owner", rpc.accountId); put("shop", rpc.shopId); put("device", rpc.deviceIdentifier)
            put("verifiedBaselineId", rpc.verifiedBaselineId); put("expectedEventMaxId", rpc.expectedEventMaxId)
            put("expectedDomainEventMaxId", rpc.expectedDomainEventMaxId); put("journalPhase", journal.phase)
            put("runId", journal.runId); put("checkpointA", journal.checkpointADigest); put("rawMetadata", journal.reason)
            put("claim", retry.claimID); put("processEpoch", retry.processEpoch); put("attemptCount", journal.attemptCount)
            put("windowAttempts", retry.windowAttempts); put("heldBeforeDelegate", shouldHold)
        })
        if (shouldHold) {
            check(held.compareAndSet(false, true))
            release.await()
            throw CancellationException("Owned hold released only during failed cleanup")
        }
    }
    fun wrap(delegate: ShopSyncReadRemoteDataSource): ShopSyncReadRemoteDataSource = object : ShopSyncReadRemoteDataSource by delegate {
        override suspend fun checkpoint(context: ShopSyncRpcContext): Result<ShopSyncRecoveryCheckpoint> {
            enter("checkpoint", context); return delegate.checkpoint(context)
        }
        override suspend fun convergenceMarker(context: ShopSyncRpcContext): Result<ShopSyncConvergenceMarker> {
            enter("marker", context); return delegate.convergenceMarker(context)
        }
        override suspend fun recoveryPage(context: ShopSyncRpcContext, domain: ShopSyncRowDomain, afterId: String?, limit: Int): Result<ShopSyncRecoveryPage> {
            enter("recoveryPage", context, domain, afterId, limit)
            return delegate.recoveryPage(context, domain, afterId, limit)
        }
    }
}

/** Assertion only. Mounting the UI never grants local scope or publishes READY. */
internal suspend fun ScopeSwitchFixture.verifyAutomaticRecoveryLocal() {
    check(context.scenario in NativeAutomaticRecoveryContract.scenarios)
    val state = tracker.businessDataScopeState.value
    assertEquals("sync_recovery_required", state.errorCode)
    assertTrue(state.allowsLocalOperations)
    val stamp = requireNotNull(tracker.captureLocalPresentationStamp(state))
    assertTrue(tracker.isLocalPresentationStampCurrent(stamp, state))
    assertEquals(Task126OwnerStoreGateDecision.Allowed,
        Task126OwnerStoreGate.validate(requireNotNull(state.localAccessScope ?: state.boundScope), NativeAckContract.scope))
    assertEquals(NativeAckContract.OWNER, shops.state.value.ownerUserId)
    assertEquals(NativeAckContract.SHOP, shops.state.value.activeShopId)
    assertTrue(shops.state.value.localAccessAllowed)
    assertEquals(SyncRecoveryAuthorizationModes.SAME_SCOPE, db.syncRecoveryJournalDao().get()?.authorizationMode)
    val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
    db.withTransaction { validateShopSyncActiveReceipt(db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson)) }
    assertTrue(repository.resolveOfflineBusinessDataScope(NativeAckContract.scope, true).allowsLocalOperations)
}

internal class NativeAutomaticRecoveryOracle(val fixture: ScopeSwitchFixture, val probe: NativeAutomaticRecoveryProbe) {
    private val db get() = fixture.db
    private val remote get() = requireNotNull(fixture.resolutionBackend)
    private val json get() = NativeResolutionContract.json
    private var runtimeStarted = false
    suspend fun journal() = requireNotNull(db.syncRecoveryJournalDao().get())
    private suspend fun ref() = requireNotNull(db.productRemoteRefDao().getByProductId(fixture.product().id))
    private fun recoveryJob(): Job? = scopeSwitchField(fixture.app, "businessRecoveryJob")
    private fun recoveryMutex(): kotlinx.coroutines.sync.Mutex = scopeSwitchField(fixture.app, "businessRecoveryExecutionMutex")
    suspend fun await(label: String, timeoutMs: Long = 30_000, predicate: suspend () -> Boolean) {
        withTimeout(timeoutMs) { while (!predicate()) delay(50) }
        fixture.record("automatic_oracle", buildJsonObject { put("condition", label) })
    }
    suspend fun prepareQualifiedRecovery() {
        fixture.seed()
        NativeResolutionOracle(fixture).qualifyCanonicalBase() // Setup only, before automatic measurement.
        assertNull(db.syncRecoveryJournalDao().get())
        val now = System.currentTimeMillis()
        db.syncRecoveryJournalDao().upsert(SyncRecoveryJournal(
            ownerHash = NativeAckContract.scope.ownerHash, storeScope = NativeAckContract.scope.storeId,
            shopId = NativeAckContract.SHOP, deviceId = NativeAckContract.DEVICE,
            authorizationMode = SyncRecoveryAuthorizationModes.SAME_SCOPE,
            phase = SyncRecoveryJournalPhases.REQUIRED, reason = "task144_native_automatic_recovery",
            blockingEventId = null, attemptCount = 0, createdAtMs = now, updatedAtMs = now, nextRetryAtMs = null))
        fixture.saveImage("qualified-before-automatic-admission")
    }
    suspend fun startAutomatic() {
        fixture.openRuntime(allowPendingRecovery = true)
        runtimeStarted = true
        // Install actual normal writer callbacks. All guards, transition and completion stay production.
        fixture.app.catalogAutoSyncCoordinator
        probe.arm()
        fixture.beginObservation() // Real scope_align -> schedulePendingBusinessRecovery; no manual repair/request.
        fixture.record("automatic_scope_observer_started")
    }
    suspend fun waitForHeldPrefix() {
        await("real_automatic_request_held") { probe.isHeld() }
        val journal = journal(); val retry = requireNotNull(AutomaticRecoveryRetryMetadata.decode(journal.reason))
        assertEquals("in_flight", retry.state); assertEquals(1, retry.windowAttempts); assertEquals(1, journal.attemptCount)
        assertNotNull(retry.processEpoch); assertNull(retry.lastCompletedAtMs)
        assertEquals(SyncRecoveryJournalPhases.STAGING, journal.phase)
        assertNotNull(journal.checkpointADigest)
        assertTrue(requireNotNull(recoveryJob()).isActive); assertTrue(recoveryMutex().isLocked)
        fixture.verifyAutomaticRecoveryLocal()
        val image = stageImage()
        val progress = image.getValue("accepted").jsonArray
        val expected = if (fixture.context.scenario == "abandoned-claim") ShopSyncRowDomain.entries.map { it.wireValue }.toSet()
            else setOf(ShopSyncRowDomain.SUPPLIERS.wireValue, ShopSyncRowDomain.CATEGORIES.wireValue, ShopSyncRowDomain.PRODUCTS.wireValue)
        assertEquals(expected, progress.map { it.jsonObject.getValue("domain").jsonPrimitive.content }.toSet())
        assertTrue(progress.all { it.jsonObject.getValue("complete").jsonPrimitive.boolean })
        val active = requireNotNull(db.syncRecoveryBaselineDao().get())
        assertNotEquals(active.generationId, journal.runId)
    }
    private suspend fun stageImage(): JsonObject {
        val journal = journal(); val name = requireNotNull(journal.stagingDatabaseName)
        assertEquals("sync_recovery_stage_${journal.runId}.db", name)
        check(fixture.context.getDatabasePath(name).isFile) // Never create a missing stage while inspecting.
        val stage = Room.databaseBuilder(fixture.context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.PRODUCTION_MIGRATIONS.toTypedArray())
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).build()
        try {
            val progress = RecoveryPageProgress(stage.openHelper.writableDatabase)
            assertEquals(journal.checkpointADigest, progress.checkpoint(journal).checkpointDigest)
            val accepted = ShopSyncRowDomain.entries.mapNotNull { domain -> progress.accepted(domain)?.let { value ->
                buildJsonObject { put("domain", domain.wireValue); put("afterId", value.afterId); put("complete", value.complete)
                    put("pages", value.pages); put("rows", value.rows); put("bytes", value.bytes); put("largestRowBytes", value.largestRowBytes) }
            } }
            val physical = automaticDbImage(stage)
            return buildJsonObject { put("accepted", JsonArray(accepted)); put("image", physical) }
        } finally { stage.close() }
    }
    private suspend fun journalFacts(): JsonObject {
        val row = journal()
        return buildJsonObject {
            put("rawMetadata", row.reason); put("attemptCount", row.attemptCount); put("phase", row.phase)
            put("runId", row.runId); put("stageName", row.stagingDatabaseName); put("checkpointA", row.checkpointADigest)
            put("checkpointB", row.checkpointBDigest); put("nextRetryAtMs", row.nextRetryAtMs)
        }
    }
    suspend fun snapshotMarker(stage: String, original: JsonObject? = null): JsonObject {
        val row = fixture.product(); val ref = ref()
        val pendingPrices = db.productPriceDao().getAllForCloudPush()
        val entries = readLocalBusinessEntries(db, NativeAckContract.OWNER, NativeAckContract.scope.storeId)
        val activeImage = fixture.image()
        val hasJournal = db.syncRecoveryJournalDao().get() != null
        val journalImage: JsonElement = if (hasJournal) journalFacts() else JsonNull
        val stagingImage: JsonElement = if (hasJournal) stageImage() else JsonNull
        val generation = requireNotNull(db.syncRecoveryBaselineDao().get()).generationId
        return buildJsonObject {
            put("protocol", NativeAutomaticRecoveryContract.PROTOCOL); put("runId", fixture.context.runId)
            put("scenario", fixture.context.scenario); put("stage", stage); put("pid", Process.myPid())
            put("wallMs", System.currentTimeMillis()); put("elapsedNs", SystemClock.elapsedRealtimeNanos())
            put("dbImage", activeImage); put("journal", journalImage); put("stageImage", stagingImage)
            put("baselineGeneration", generation)
            put("productId", row.id); put("productName", row.productName); put("retailPrice", row.retailPrice)
            put("remoteId", ref.remoteId); put("revision", ref.localChangeRevision); put("lastSynced", ref.lastSyncedLocalRevision)
            put("changedFields", ref.localChangedFields)
            put("pendingPrices", buildJsonArray { pendingPrices.forEach { price -> add(buildJsonObject {
                put("id", price.id); put("productId", price.productId); put("type", price.type); put("price", price.price)
                put("effectiveAt", price.effectiveAt); put("createdAt", price.createdAt); put("source", price.source); put("note", price.note)
                put("parentRemoteId", price.productRemoteId); put("remoteId", price.existingPriceRemoteId)
            }) } })
            put("localProofs", JsonArray(entries.filter { it.eventType == BUSINESS_RESOLUTION_EVENT }.map { entry ->
                buildJsonObject { put("id", entry.id); put("key", entry.clientEventId); put("raw", entry.metadataJson) }
            }))
            put("server", json.encodeToJsonElement(remote.state())); put("serverSha256", nativeSha(remote.file.readBytes()))
            put("probeSha256", probe.hash()); put("probeRows", JsonArray(probe.rows()))
            put("originalMarker", original ?: JsonNull)
        }
    }
    fun publish(name: String, marker: JsonObject) {
        val file = File(fixture.context.root, name); check(!file.exists())
        nativeDurableWrite(file, marker.toString().encodeToByteArray())
        fixture.record("process_marker_published", buildJsonObject { put("name", name); put("sha256", nativeSha(file.readBytes())) })
    }
    suspend fun verifyReopen(name: String): JsonObject {
        val marker = json.parseToJsonElement(File(fixture.context.root, name).readText()).jsonObject
        assertEquals(NativeAutomaticRecoveryContract.PROTOCOL, marker.getValue("protocol").jsonPrimitive.content)
        assertEquals(fixture.context.runId, marker.getValue("runId").jsonPrimitive.content)
        assertEquals(fixture.context.scenario, marker.getValue("scenario").jsonPrimitive.content)
        assertNotEquals(marker.getValue("pid").jsonPrimitive.int, Process.myPid())
        checkClock(marker)
        assertEquals(marker.getValue("dbImage"), fixture.image())
        assertEquals(marker.getValue("serverSha256").jsonPrimitive.content, nativeSha(remote.file.readBytes()))
        assertEquals(marker.getValue("server"), json.encodeToJsonElement(remote.state()))
        assertEquals(marker["probeSha256"]?.jsonPrimitive?.contentOrNull, probe.hash())
        if (marker.getValue("journal") != JsonNull) {
            assertEquals(marker.getValue("journal"), journalFacts())
            assertEquals(marker.getValue("stageImage"), stageImage())
        } else assertNull(db.syncRecoveryJournalDao().get())
        fixture.record("process_reopen_verified", buildJsonObject { put("priorPid", marker.getValue("pid")); put("marker", name) })
        return marker
    }
    suspend fun verifyRequalification(original: JsonObject): Long {
        await("one_durable_requalification") { db.syncRecoveryJournalDao().get()?.let {
            AutomaticRecoveryRetryMetadata.decode(it.reason)?.state == "requalifying"
        } == true }
        val prior = original.getValue("journal").jsonObject
        val raw = prior.getValue("rawMetadata").jsonPrimitive.content
        val old = requireNotNull(AutomaticRecoveryRetryMetadata.decode(raw))
        val journal = journal(); val now = requireNotNull(AutomaticRecoveryRetryMetadata.decode(journal.reason))
        val observed = requireNotNull(now.requalificationObservedAtMs)
        assertEquals(old.claimID, now.claimID); assertEquals(old.windowAttempts, now.windowAttempts)
        assertEquals(prior.getValue("attemptCount").jsonPrimitive.int, journal.attemptCount)
        assertEquals(old.lastAttemptAtMs, now.lastAttemptAtMs); assertEquals(old.lastCompletedAtMs, now.lastCompletedAtMs)
        assertNotEquals(old.processEpoch, now.processEpoch)
        assertEquals(nativeSha(raw.encodeToByteArray()), now.previousClaimSha256)
        val due = maxOf(prior["nextRetryAtMs"]?.jsonPrimitive?.longOrNull ?: 0L, old.nextEligibleAtMs, observed + 300_000L)
        assertEquals(due, now.nextEligibleAtMs); assertEquals(due, requireNotNull(journal.nextRetryAtMs))
        assertTrue("This process must really observe unexpired policy waiting", System.currentTimeMillis() < due - 5_000)
        assertTrue(requireNotNull(recoveryJob()).isActive); assertFalse(recoveryMutex().isLocked)
        assertTrue(probe.rows().none { it.getValue("pid").jsonPrimitive.int == Process.myPid() })
        fixture.verifyAutomaticRecoveryLocal()
        fixture.record("requalification_deadline_persisted", buildJsonObject {
            put("observedAtMs", observed); put("dueMs", due); put("rawMetadata", journal.reason)
            put("priorRawSha256", nativeSha(raw.encodeToByteArray())); put("attemptCount", journal.attemptCount)
        })
        return due
    }
    suspend fun finishAfterRealDue(original: JsonObject, waiting: JsonObject? = null) {
        val raw = if (waiting == null) journal().reason else waiting.getValue("journal").jsonObject.getValue("rawMetadata").jsonPrimitive.content
        val waitingMeta = requireNotNull(AutomaticRecoveryRetryMetadata.decode(raw))
        assertEquals("requalifying", waitingMeta.state)
        val due = waitingMeta.nextEligibleAtMs
        val job = requireNotNull(recoveryJob())
        val oldServer = original.getValue("server").jsonObject
        while (System.currentTimeMillis() < due - 250) {
            checkClock(waiting ?: original)
            assertEquals(raw, journal().reason)
            assertEquals(due, requireNotNull(journal().nextRetryAtMs))
            assertSame(job, recoveryJob()); assertFalse(recoveryMutex().isLocked)
            assertTrue(probe.rows().none { it.getValue("pid").jsonPrimitive.int == Process.myPid() })
            assertEquals(oldServer.getValue("calls"), json.encodeToJsonElement(remote.state()).jsonObject.getValue("calls"))
            delay(100)
        }
        await("automatic_recovery_and_pending_drained", 100_000) {
            db.syncRecoveryJournalDao().get() == null && recoveryJob() == null &&
                fixture.tracker.businessDataScopeState.value.status == Task126BusinessDataScopeStatus.READY &&
                !fixture.repository.hasCatalogCloudPendingWorkInclusive()
        }
        val requests = probe.rows().filter { it.getValue("pid").jsonPrimitive.int == Process.myPid() }
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.getValue("wallMs").jsonPrimitive.long >= due })
        assertEquals(1, requests.map { it.getValue("claim").jsonPrimitive.content }.distinct().size)
        assertEquals(1, requests.map { it.getValue("processEpoch").jsonPrimitive.content }.distinct().size)
        val originalEpoch = requireNotNull(AutomaticRecoveryRetryMetadata.decode(original.getValue("journal").jsonObject
            .getValue("rawMetadata").jsonPrimitive.content)).processEpoch
        assertTrue(requests.none { it.getValue("processEpoch").jsonPrimitive.content == originalEpoch })
        if (waiting != null) assertTrue(requests.none { it.getValue("processEpoch").jsonPrimitive.content == waitingMeta.processEpoch })
        assertTrue(requests.none { it.getValue("claim").jsonPrimitive.content == waitingMeta.claimID })
        assertTrue(requests.all { it.getValue("attemptCount").jsonPrimitive.int ==
            original.getValue("journal").jsonObject.getValue("attemptCount").jsonPrimitive.int + 1 })
        assertTrue(requests.all { it.getValue("windowAttempts").jsonPrimitive.int == waitingMeta.windowAttempts + 1 })
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        assertEquals(original.getValue("journal").jsonObject.getValue("runId").jsonPrimitive.content, baseline.generationId)
        assertNotEquals(original.getValue("baselineGeneration").jsonPrimitive.content, baseline.generationId)
        fixture.verifyResolutionReady()
        val accepted = original.getValue("stageImage").jsonObject.getValue("accepted").jsonArray
        for (entry in accepted) {
            val domain = entry.jsonObject.getValue("domain").jsonPrimitive.content
            assertEquals("Accepted complete domain must not be downloaded twice", 1, probe.rows().count {
                it.getValue("method").jsonPrimitive.content == "recoveryPage" && it["domain"]?.jsonPrimitive?.contentOrNull == domain
            })
        }
        val stageName = original.getValue("journal").jsonObject.getValue("stageName").jsonPrimitive.content
        assertFalse(fixture.context.getDatabasePath(stageName).exists())
        if (fixture.context.scenario == "save-before-activation") verifySavedMaterial(original)
        else {
            assertEquals(original.getValue("productName").jsonPrimitive.content, fixture.product().productName)
            assertEquals(original.getValue("revision").jsonPrimitive.int, ref().localChangeRevision)
            assertEquals(oldServer.getValue("calls"), json.encodeToJsonElement(remote.state()).jsonObject.getValue("calls"))
        }
        fixture.record("automatic_recovery_complete", buildJsonObject { put("dueMs", due); put("generation", baseline.generationId) })
    }
    suspend fun verifySavedBeforeKill(): JsonObject {
        val row = fixture.product(); val ref = ref()
        assertEquals(NativeAutomaticRecoveryContract.SAVED_NAME, row.productName)
        assertEquals(NativeAutomaticRecoveryContract.SAVED_RETAIL, requireNotNull(row.retailPrice), 0.0)
        assertTrue(ref.localChangeRevision > ref.lastSyncedLocalRevision)
        assertEquals(setOf("productname", "retailprice"), ref.localChangedFields?.split(',')?.toSet())
        val child = db.productPriceDao().getAllForCloudPush().single { it.productId == row.id && it.type == "RETAIL" && it.price == NativeAutomaticRecoveryContract.SAVED_RETAIL }
        assertNull(child.existingPriceRemoteId)
        assertEquals(1, db.productPriceDao().getAllForCloudPush().size)
        val ledger = requireNotNull(loadBusinessResolutionSnapshot(db, NativeAckContract.OWNER, NativeAckContract.scope.storeId)).requireValid()
        val intent = ledger.intents.single { it.remoteId == ref.remoteId && it.intent?.revision == ref.localChangeRevision }.intent!!
        assertEquals(setOf("productname", "retailprice"), intent.intendedFields.toSet())
        assertEquals(row.productName, intent.desired.productName)
        assertEquals(row.retailPrice, intent.desired.retailPrice)
        assertTrue(intent.dispatchOperationKeys.isEmpty())
        assertFalse(fixture.repository.observeProductCloudConfirmed(row.id).first())
        assertNotEquals(row.productName, remote.state().product?.productName)
        assertEquals(5.0, requireNotNull(remote.state().product?.retailPrice), 0.0)
        assertTrue(probe.isHeld()); assertEquals(SyncRecoveryJournalPhases.STAGING, journal().phase)
        return snapshotMarker("saved-before-activation")
    }
    suspend fun verifySavedMaterial(original: JsonObject) {
        val product = fixture.product(); val ref = ref()
        assertEquals(original.getValue("productId").jsonPrimitive.long, product.id)
        assertEquals(original.getValue("remoteId").jsonPrimitive.content, ref.remoteId)
        assertEquals(original.getValue("revision").jsonPrimitive.int, ref.localChangeRevision)
        assertEquals(ref.localChangeRevision, ref.lastSyncedLocalRevision)
        assertEquals(NativeAutomaticRecoveryContract.SAVED_NAME, product.productName)
        assertEquals(NativeAutomaticRecoveryContract.SAVED_RETAIL, requireNotNull(product.retailPrice), 0.0)
        val server = remote.state(); val serverProduct = requireNotNull(server.product)
        assertEquals(ref.remoteId, serverProduct.id); assertEquals(product.productName, serverProduct.productName)
        assertEquals(product.retailPrice, serverProduct.retailPrice)
        val originalProofs = original.getValue("localProofs").jsonArray
        val entries = readLocalBusinessEntries(db, NativeAckContract.OWNER, NativeAckContract.scope.storeId)
        originalProofs.forEach { proof -> val value = proof.jsonObject
            val entry = entries.single { it.id == value.getValue("id").jsonPrimitive.long }
            assertEquals(value.getValue("key").jsonPrimitive.content, entry.clientEventId)
            assertEquals(value.getValue("raw").jsonPrimitive.content, entry.metadataJson)
        }
        val ledger = requireNotNull(loadBusinessResolutionSnapshot(db, NativeAckContract.OWNER, NativeAckContract.scope.storeId)).requireValid()
        val transfer = ledger.records.single { it.kind == "generation_transfer" &&
            it.transfer?.previousGeneration == original.getValue("baselineGeneration").jsonPrimitive.content }.transfer!!
        assertTrue(transfer.transferredRecordHashes.containsAll(originalProofs.map { nativeSha(it.jsonObject.getValue("raw").jsonPrimitive.content.encodeToByteArray()) }))
        assertEquals(original.getValue("dbImage").jsonObject.getValue("product_prices"),
            fixture.image().getValue("product_prices"))
        val child = original.getValue("pendingPrices").jsonArray.single().jsonObject
        val childId = child.getValue("id").jsonPrimitive.long
        val physical = db.productPriceDao().getForProducts(listOf(product.id)).single { it.id == childId }
        assertEquals(child.getValue("price").jsonPrimitive.double, physical.price, 0.0)
        assertEquals(child.getValue("effectiveAt").jsonPrimitive.content, physical.effectiveAt)
        assertEquals(child.getValue("createdAt").jsonPrimitive.content, physical.createdAt)
        assertEquals(child["source"]?.jsonPrimitive?.contentOrNull, physical.source)
        assertEquals(child["note"]?.jsonPrimitive?.contentOrNull, physical.note)
        val bridge = requireNotNull(db.productPriceRemoteRefDao().getByProductPriceId(childId))
        val remoteChild = server.prices.single { it.id == bridge.remoteId }
        assertEquals(ref.remoteId, remoteChild.productId); assertEquals(physical.price, remoteChild.price, 0.0)
        assertEquals(physical.type.uppercase(), remoteChild.type.uppercase())
        assertEquals(physical.effectiveAt, remoteChild.effectiveAt); assertEquals(physical.createdAt, remoteChild.createdAt)
        assertEquals(physical.source, remoteChild.source); assertEquals(physical.note, remoteChild.note)
        assertEquals(original.getValue("server").jsonObject.getValue("prices"),
            json.encodeToJsonElement(server.prices.filterNot { it.id == bridge.remoteId }))
        val oldCalls = original.getValue("server").jsonObject.getValue("calls").jsonArray.size
        val calls = server.calls.drop(oldCalls)
        assertEquals("One product mutation and its one distinct historical-price write", 2, calls.size)
        val productCall = calls.single { it.patchId == ref.remoteId && it.patch != null }
        assertEquals(setOf("productname", "retailprice"), productCall.patch!!.changedFields.toSet())
        assertEquals("committed_returned", productCall.outcome)
        assertEquals(ref.localChangeRevision, requireNotNull(productCall.parentLocalRevision))
        val priceCall = calls.single { it.prices.any { price -> price.id == bridge.remoteId } }
        assertEquals("committed_returned", priceCall.outcome)
        assertEquals(bridge.remoteId, priceCall.prices.single().id)
        assertEquals(ref.localChangeRevision, requireNotNull(priceCall.parentAcknowledgedRevision))
        assertNotNull(productCall.rawAttempt); assertNotNull(priceCall.rawAttempt)
        assertTrue(db.productPriceDao().getAllForCloudPush().isEmpty())
        assertFalse(fixture.repository.hasCatalogCloudPendingWorkInclusive())
        assertTrue(fixture.repository.observeProductCloudConfirmed(product.id).first())
        assertTrue("Local proof retained is not cloud pending", entries.any { it.eventType == BUSINESS_RESOLUTION_EVENT })
    }
    /** Exact reopen was checked before runtime. Now require a new real automatic opportunity;
     * an unchanged server before asynchronous observation starts cannot prove no retransmission. */
    suspend fun verifySecondReopenAutomaticOpportunity(completed: JsonObject, saved: JsonObject) {
        fixture.openRuntime()
        runtimeStarted = true
        val beforeOutcome = fixture.tracker.lastQualifiedOutcome.value
        val beforeServer = remote.state()
        val readOffset = beforeServer.reads.size
        val startedAt = System.currentTimeMillis()
        val expectedServer = completed.getValue("server").jsonObject
        val expectedImage = completed.getValue("dbImage").jsonObject
        var primary: Throwable? = null
        var verified = false
        fixture.record("second_reopen_ordinary_before", buildJsonObject {
            put("startedAtMs", startedAt); put("readOffset", readOffset)
            put("outcome", ordinaryOutcomeFacts(beforeOutcome)); put("expectedServer", expectedServer)
            put("expectedDbImage", expectedImage)
        })
        try {
            fixture.app.catalogAutoSyncCoordinator // Actual initialization may schedule auth_signed_in automatically.
            fixture.beginObservation() // Actual scope alignment also schedules shop_context_changed. No manual push/drain.
            await("second_reopen_fresh_current_ordinary_outcome") {
                val outcome = fixture.tracker.lastQualifiedOutcome.value
                outcome != null && outcome !== beforeOutcome && outcome.source == CatalogSyncFlightOwner.SYNC_EVENTS &&
                    outcome.completedAtMs?.let { it >= startedAt } == true &&
                    fixture.tracker.isOutcomeCurrent(outcome, NativeAckContract.OWNER, fixture.shops.state.value.selectedShop) &&
                    !outcome.summary.manualFullSyncRequired && !outcome.summary.syncEventsOrdinaryPending &&
                    !outcome.summary.priceSyncFailed && !outcome.summary.syncEventsGapDetected && !outcome.summary.syncEventsTooLarge &&
                    outcome.summary.syncEventsAvailable && !outcome.summary.syncEventsDisabled && !outcome.summary.syncEventsFallback044 &&
                    outcome.summary.syncEventOutboxPending == 0 && outcome.summary.syncEventOutboxRetried == 0 &&
                    outcome.summary.syncEventsSkippedDirtyLocal == 0 && !fixture.tracker.isSyncing.value &&
                    remote.state().reads.drop(readOffset).any(::isQualifiedOrdinaryRead)
            }
            fixture.verifyResolutionReady()
            val afterServer = remote.state()
            assertEquals(beforeServer.reads, afterServer.reads.take(readOffset))
            assertTrue(afterServer.reads.drop(readOffset).any(::isQualifiedOrdinaryRead))
            assertFalse(afterServer.reads.drop(readOffset).any {
                it["method"]?.jsonPrimitive?.contentOrNull == "unexpectedOrdinaryWakeEventWrite"
            })
            // Keep all diagnostic reads in the witness; only reads are excluded from server business invariance.
            assertEquals(JsonObject(expectedServer.filterKeys { it != "reads" }),
                JsonObject(json.encodeToJsonElement(afterServer).jsonObject.filterKeys { it != "reads" }))
            val afterImage = fixture.image()
            for (table in listOf("suppliers", "categories", "products", "product_prices", "history_entries",
                "supplier_remote_refs", "category_remote_refs", "product_price_remote_refs", "history_entry_remote_refs",
                "pending_catalog_tombstones", "sync_event_outbox", "sync_event_device_state", "business_data_scope_binding")) {
                assertEquals("Business material, identities and raw intent/ACK ledger must survive an ordinary opportunity: $table",
                    expectedImage.getValue(table), afterImage.getValue(table))
            }
            assertEquals(productRefLocalState(expectedImage), productRefLocalState(afterImage))
            if (expectedImage.getValue("product_remote_refs") != afterImage.getValue("product_remote_refs")) {
                val observedRef = ref()
                val canonical = requireNotNull(afterServer.product)
                assertEquals(canonical.id, observedRef.remoteId)
                assertEquals(canonical.updatedAt, observedRef.remoteUpdatedAt)
                assertEquals(fingerprintProductInbound(canonical), observedRef.lastRemotePayloadFingerprint)
                assertTrue(requireNotNull(observedRef.lastRemoteAppliedAt) in startedAt..System.currentTimeMillis())
            }
            val baselineBefore = imageRows(expectedImage, "sync_recovery_baseline").single()
            val baselineAfter = imageRows(afterImage, "sync_recovery_baseline").single()
            val receiptObservationFields = setOf("checkpointJson", "activatedAtMs")
            assertEquals(JsonObject(baselineBefore.filterKeys { it !in receiptObservationFields }),
                JsonObject(baselineAfter.filterKeys { it !in receiptObservationFields }))
            assertNull(db.syncRecoveryJournalDao().get())
            val active = requireNotNull(db.syncRecoveryBaselineDao().get())
            if (baselineBefore.getValue("activatedAtMs") != baselineAfter.getValue("activatedAtMs")) {
                assertTrue(active.activatedAtMs in startedAt..System.currentTimeMillis())
            }
            assertEquals(completed.getValue("baselineGeneration").jsonPrimitive.content, active.generationId)
            val checkpoint = decodeRecoveryCheckpointJson(active.checkpointJson)
            assertEquals(afterServer.events.size.toString(), checkpoint.syncEvents.maxId)
            assertEquals(afterServer.events.size.toLong(), db.syncEventWatermarkDao()
                .get(NativeAckContract.OWNER, NativeAckContract.scope.storeId)?.lastSyncEventId)
            db.withTransaction { validateShopSyncActiveReceipt(db, active.generationId, checkpoint) }
            verifySavedMaterial(saved)
            verified = true
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                // Persist the first failed opportunity as well as success. A diagnostic failure never replaces it.
                try {
                    val lastOutcome = fixture.tracker.lastQualifiedOutcome.value
                    val lastServer = json.encodeToJsonElement(remote.state())
                    fixture.record("second_reopen_ordinary_after", buildJsonObject {
                        put("startedAtMs", startedAt); put("readOffset", readOffset); put("verified", verified)
                        put("failureType", primary?.javaClass?.name); put("outcome", ordinaryOutcomeFacts(lastOutcome))
                        put("server", lastServer)
                    })
                    // Preserve outcome/remote reads before another database read can fail.
                    val lastImage = fixture.image()
                    fixture.record("second_reopen_ordinary_db_after", buildJsonObject { put("dbImage", lastImage) })
                } catch (evidenceFailure: Throwable) {
                    val failure = primary
                    if (failure == null) throw evidenceFailure
                    if (failure !== evidenceFailure) failure.addSuppressed(evidenceFailure)
                }
            }
        }
    }
    private fun isQualifiedOrdinaryRead(read: JsonObject): Boolean =
        read["method"]?.jsonPrimitive?.contentOrNull in setOf("checkpoint", "marker", "eventPage") &&
            read["account"]?.jsonPrimitive?.contentOrNull == NativeAckContract.OWNER &&
            read["shop"]?.jsonPrimitive?.contentOrNull == NativeAckContract.SHOP &&
            read["device"]?.jsonPrimitive?.contentOrNull == NativeAckContract.DEVICE

    private fun ordinaryOutcomeFacts(outcome: CatalogSyncOutcomeState?): JsonElement = outcome?.let {
        buildJsonObject {
            put("owner", it.ownerUserId); put("source", it.source.name); put("completedAtMs", it.completedAtMs)
            put("current", fixture.tracker.isOutcomeCurrent(it, NativeAckContract.OWNER, fixture.shops.state.value.selectedShop))
            put("summary", it.summary.toString())
        }
    } ?: JsonNull

    private fun imageRows(image: JsonObject, table: String): List<JsonObject> =
        image.getValue(table).jsonArray.map { json.parseToJsonElement(it.jsonPrimitive.content).jsonObject }

    private fun productRefLocalState(image: JsonObject): JsonArray = JsonArray(
        imageRows(image, "product_remote_refs").map { row ->
            // Inbound observation timestamps/version/fingerprint may advance after a Unit ACK. Local identity,
            // revision, ACK revision and dirty mask must not. The full unfiltered row remains in the witness.
            buildJsonObject { for (field in listOf("id", "productId", "remoteId", "localChangeRevision",
                "lastSyncedLocalRevision", "localChangedFields")) put(field, row.getValue(field)) }
        }.sortedBy { it.toString() })

    fun cancelBeforeReleasingFailedHold() {
        if (runtimeStarted) scopeSwitchField<CoroutineScope>(fixture.app, "appScope").coroutineContext[Job]?.cancel()
        probe.releaseForFailedCleanup()
    }
    private fun checkClock(marker: JsonObject) {
        val wall = System.currentTimeMillis() - marker.getValue("wallMs").jsonPrimitive.long
        val mono = (SystemClock.elapsedRealtimeNanos() - marker.getValue("elapsedNs").jsonPrimitive.long) / 1_000_000
        assertTrue("Same boot monotonic witness required", mono >= 0)
        assertTrue("Clock discontinuity makes due witness unqualified", abs(wall - mono) <= 2_500)
    }
}

private suspend fun automaticDbImage(db: AppDatabase): JsonObject = db.withTransaction {
    val sql = db.openHelper.readableDatabase
    val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY name").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    buildJsonObject { for (table in tables) {
        check(Regex("[A-Za-z0-9_]+").matches(table))
        val rows = sql.query("SELECT * FROM `$table`").use { cursor -> buildList {
            while (cursor.moveToNext()) add(buildJsonObject { cursor.columnNames.forEachIndexed { index, column ->
                put(column, when (cursor.getType(index)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> "null"
                    android.database.Cursor.FIELD_TYPE_BLOB -> "blob:" + android.util.Base64.encodeToString(cursor.getBlob(index), android.util.Base64.NO_WRAP)
                    else -> "${cursor.getType(index)}:" + cursor.getString(index)
                })
            } }.toString())
        }.sorted() }
        put(table, JsonArray(rows.map(::JsonPrimitive)))
    } }
}
