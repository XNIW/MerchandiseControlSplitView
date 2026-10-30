package com.example.merchandisecontrolsplitview.data

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.merchandisecontrolsplitview.util.CatalogTextField
import com.example.merchandisecontrolsplitview.util.CatalogTextPolicy
import com.example.merchandisecontrolsplitview.util.CatalogTextValidationException
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native service/Room witnesses with synthetic transports. No UI, scheduler or live-server claim. */
@RunWith(AndroidJUnit4::class)
class Task144IndependentProgressDeviceTest {
    /** Fixture-only oracle guard, not an additional product acceptance scenario. */
    @Test
    fun wrongShopTransportEntryIsRecordedBeforeRejection(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(15_000) {
            val context = t11Context("ledger-guard", fresh = true)
            T11Fixture(context).use { f ->
                val before = f.snapshots() // Empty owned Room: no product seed or production push.
                val foreign = "14400000-0000-4000-8000-000000000099"
                for (kind in listOf("SUPPLIERS", "CATEGORIES", "PRODUCTS")) {
                    val ledgerBefore = f.backend.records()
                    val failure = runCatching {
                        when (kind) {
                            "SUPPLIERS" -> f.backend.upsertSuppliers(emptyList(), foreign)
                            "CATEGORIES" -> f.backend.upsertCategories(emptyList(), foreign)
                            else -> f.backend.upsertProducts(emptyList(), foreign)
                        }
                    }.exceptionOrNull()
                    assertTrue(failure is IllegalStateException)
                    assertEquals("fixture_shop_mismatch", failure?.message)
                    val records = f.backend.records()
                    assertEquals(ledgerBefore, records.take(ledgerBefore.size))
                    val added = records.drop(ledgerBefore.size)
                    assertEquals(1, added.size)
                    assertEquals("BUSINESS_ENTRY", added.single().getValue("kind").jsonPrimitive.content)
                    assertEquals(kind, added.single().getValue("domain").jsonPrimitive.content)
                    assertTrue(added.single().getValue("explicitShopArgument").jsonPrimitive.boolean)
                    assertEquals(foreign, added.single().getValue("observedShopArgument").jsonPrimitive.content)
                    assertTrue(added.single().getValue("wireRows").jsonArray.isEmpty())
                    assertEquals(before, f.snapshots())
                }
                assertEquals(listOf("BUSINESS_ENTRY", "BUSINESS_ENTRY", "BUSINESS_ENTRY"),
                    f.backend.records().map { it.getValue("kind").jsonPrimitive.content })
                f.saveEvidence("verified.json", f.evidence("fixture-only wrong-shop entry guard"))
            }
        }
    }

    @Test
    fun localSupplier161KeepsPAndAcknowledgesQInOneInvocation(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(90_000) {
            val context = t11Context("local-supplier", fresh = true)
            var expected = emptyMap<String, String>()
            var ledgerAfter = emptyList<JsonObject>()
            T11Fixture(context).use { f ->
                f.seed()
                val supplier = requireNotNull(f.repository.addSupplier("Rejected supplier draft"))
                val supplierIdentity = requireNotNull(f.db.supplierRemoteRefDao().getBySupplierId(supplier.id)).remoteId
                f.repository.renameCatalogEntry(CatalogEntityKind.SUPPLIER, supplier.id, "Rejected supplier")
                val p = f.addEditedProduct("T11-NATIVE-P", "Dependent P", supplier.id)
                val q = f.addEditedProduct("T11-NATIVE-Q", "Independent Q")
                // Only this legacy-input injection bypasses authoring validation. Real writes above
                // already established identity and dirty revisions. Preserve the exact preimage first.
                f.saveEvidence("legacy-preimage.json", f.evidence())
                f.db.supplierDao().rename(supplier.id, "X".repeat(CatalogTextPolicy.Limits.SUPPLIER_NAME + 1))
                val invalid = requireNotNull(f.repository.getSupplierById(supplier.id))
                val sBefore = requireNotNull(f.db.supplierRemoteRefDao().getBySupplierId(supplier.id))
                val pBefore = requireNotNull(f.db.productRemoteRefDao().getByProductId(p.id))
                val qBefore = requireNotNull(f.db.productRemoteRefDao().getByProductId(q.id))
                assertEquals(supplierIdentity, sBefore.remoteId)
                assertTrue(sBefore.localChangeRevision > sBefore.lastSyncedLocalRevision)
                assertTrue(pBefore.localChangeRevision > pBefore.lastSyncedLocalRevision)
                assertTrue(qBefore.localChangeRevision > qBefore.lastSyncedLocalRevision)
                assertNull(sBefore.lastRemoteAppliedAt); assertNull(pBefore.lastRemoteAppliedAt); assertNull(qBefore.lastRemoteAppliedAt)
                assertEquals(supplier.id, p.supplierId); assertNull(q.supplierId)
                assertEquals(1, f.db.supplierDao().count()); assertEquals(2, f.db.productDao().count())
                assertEquals(0, f.db.syncEventOutboxDao().countAll()); assertTrue(f.backend.records().isEmpty())
                val typed = runCatching { CatalogTextCanonicalizer.supplierName(invalid.name) }.exceptionOrNull()
                assertTrue(typed is CatalogTextValidationException)
                typed as CatalogTextValidationException
                assertEquals(CatalogTextField.SUPPLIER_NAME, typed.rejection.field)
                assertEquals(CatalogTextPolicy.RejectionReason.TOO_LONG, typed.rejection.reason)
                val before = f.snapshots()
                f.saveEvidence("exercise-preimage.json", f.evidence())
                val result = f.push() // Exactly one call: no second push or Retry to rescue Q.
                f.saveEvidence("exercise-observation.json", f.evidence(result.exceptionOrNull()?.message))
                assertT11ReaderTracksAcknowledgedQ(f, qBefore.remoteId)
                val failure = result.exceptionOrNull()
                assertTrue(failure is CatalogTextValidationException)
                assertEquals(typed.rejection, (failure as CatalogTextValidationException).rejection)
                assertEquals(sBefore, f.db.supplierRemoteRefDao().getBySupplierId(supplier.id))
                assertEquals(pBefore, f.db.productRemoteRefDao().getByProductId(p.id))
                val qAfter = requireNotNull(f.db.productRemoteRefDao().getByProductId(q.id))
                assertEquals(qBefore.remoteId, qAfter.remoteId)
                assertEquals(qBefore.localChangeRevision, qAfter.localChangeRevision)
                assertEquals(qBefore.localChangeRevision, qAfter.lastSyncedLocalRevision)
                assertNotNull(qAfter.lastRemoteAppliedAt); assertNotNull(qAfter.lastRemotePayloadFingerprint)
                assertNull(qAfter.localChangedFields)
                assertEquals(listOf("BUSINESS_ENTRY", "BUSINESS_ATTEMPT", "BUSINESS_ACK", "EVENT_ATTEMPT", "EVENT_ACK"),
                    f.backend.records().map { it.getValue("kind").jsonPrimitive.content })
                val writes = f.backend.records().filterKind("BUSINESS_ATTEMPT")
                assertEquals(1, writes.size)
                assertEquals("PRODUCTS", writes.single().getValue("domain").jsonPrimitive.content)
                assertEquals(listOf(qBefore.remoteId), writes.single().getValue("ids").jsonArray.map { it.jsonPrimitive.content })
                assertEquals(1, f.backend.records().filterKind("BUSINESS_ACK").size)
                val notifications = f.backend.records().filterKind("EVENT_ATTEMPT")
                assertEquals(1, notifications.size)
                val params = T11Contract.json.decodeFromString<SyncEventRecordRpcParams>(notifications.single().getValue("rawParams").jsonPrimitive.content)
                assertEquals(SyncEventEntityIds(productIds = listOf(qBefore.remoteId)), params.entityIds)
                assertEquals(1, f.backend.records().filterKind("EVENT_ACK").size)
                assertEquals(0, f.db.syncEventOutboxDao().countAll())
                assertEquals(setOf(p.id), f.db.productDao().getCatalogPushCandidates().map { it.product.id }.toSet())
                assertTrue(f.repository.hasCatalogCloudPendingWorkInclusive())
                listOf("products", "suppliers", "supplier_remote_refs", "sync_recovery_baseline", "business_data_scope_binding").forEach {
                    assertEquals("Preserved $it", before.getValue(it), f.snapshot(it))
                }
                f.assertUnrelatedDomainsUntouched()
                expected = f.snapshots(); ledgerAfter = f.backend.records()
            }
            T11Fixture(T11OwnedContext(context.baseContext, context.runId, context.scenario, fresh = false)).use { reopened ->
                assertEquals(expected, reopened.snapshots()); assertEquals(ledgerAfter, reopened.backend.records())
                reopened.qualify()
                assertEquals(expected, reopened.snapshots())
                assertTrue(reopened.repository.hasCatalogCloudPendingWorkInclusive())
                reopened.saveEvidence("verified.json", reopened.evidence("typed local failure retained; Q ACK in one invocation"))
            }
        }
    }

    @Test
    fun malformedNotificationHeadPreservesAckedRowsAndDrainsIndependentEvent(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            val context = t11Context("malformed-event", fresh = true)
            var expected = emptyMap<String, String>()
            var ledgerAfter = emptyList<JsonObject>()
            T11Fixture(context).use { f ->
                f.seed(); f.backend.failNotifications = true
                val first = f.addEditedProduct("T11-NATIVE-FIRST", "First ACK")
                f.push().getOrThrow()
                val second = f.addEditedProduct("T11-NATIVE-SECOND", "Second ACK")
                f.push().getOrThrow()
                val pending = f.db.syncEventOutboxDao().listPending(T11Contract.OWNER, 10)
                assertEquals(2, pending.size)
                assertTrue(pending.all { it.eventType == SyncEventTypes.CATALOG_CHANGED })
                val firstRef = requireNotNull(f.db.productRemoteRefDao().getByProductId(first.id))
                val secondRef = requireNotNull(f.db.productRemoteRefDao().getByProductId(second.id))
                listOf(firstRef, secondRef).forEach {
                    assertEquals(it.localChangeRevision, it.lastSyncedLocalRevision)
                    assertNotNull(it.lastRemoteAppliedAt); assertNotNull(it.lastRemotePayloadFingerprint)
                }
                assertFalse(f.db.productRemoteRefDao().hasPendingWork())
                assertEquals(2, f.backend.records().filterKind("BUSINESS_ACK").size)
                assertEquals(SyncEventEntityIds(productIds = listOf(firstRef.remoteId)), T11Contract.json.decodeFromString<SyncEventEntityIds>(pending[0].entityIdsJson))
                assertEquals(SyncEventEntityIds(productIds = listOf(secondRef.remoteId)), T11Contract.json.decodeFromString<SyncEventEntityIds>(pending[1].entityIdsJson))
                // Decode real persisted completion metadata, never synthesize a deadline or clock.
                val retry = pending.map { requireNotNull(SyncEventRetryMetadata.decode(requireNotNull(it.lastErrorType))) }
                assertTrue(retry.all { it.phase == "waiting" && it.isTransient && it.lastCompletedAtMs != null })
                val due = retry.maxOf { it.nextEligibleAtMs }
                val observedAt = System.currentTimeMillis()
                assertTrue("Fixture must stay within the existing non-exhausted retry window", due - observedAt <= 60_000L)
                f.saveEvidence("corruption-preimage.json", f.evidence())
                val corrupted = pending[0].copy(entityIdsJson = "{not-valid-json")
                f.db.syncEventOutboxDao().update(corrupted)
                assertEquals(corrupted, f.db.syncEventOutboxDao().getById(corrupted.id))
                f.saveEvidence("corruption-readback.json", f.evidence())
                val before = f.snapshots()
                val ledgerBefore = f.backend.records()
                f.backend.failNotifications = false
                val elapsedStart = SystemClock.elapsedRealtime()
                while (System.currentTimeMillis() < due) delay(minOf(250L, due - System.currentTimeMillis()).coerceAtLeast(1L))
                val admittedAt = System.currentTimeMillis()
                f.saveEvidence("actual-deadline.json", buildJsonObject {
                    put("observedAtMs", observedAt); put("nextEligibleAtMs", due); put("admittedAtMs", admittedAt)
                    put("elapsedWaitMs", SystemClock.elapsedRealtime() - elapsedStart)
                    put("metadata", JsonArray(pending.map { JsonPrimitive(requireNotNull(it.lastErrorType)) }))
                })
                assertTrue(admittedAt >= due)
                val result = f.drain() // One real drain after measured real time, no retry-clock override.
                f.saveEvidence("exercise-observation.json", f.evidence(result.exceptionOrNull()?.message))
                assertTrue(result.toString(), result.isSuccess)
                val added = f.backend.records().drop(ledgerBefore.size)
                assertEquals(listOf("EVENT_ATTEMPT", "EVENT_ACK"), added.map { it.getValue("kind").jsonPrimitive.content })
                assertTrue(added.all { it.getValue("clientEventId").jsonPrimitive.content == pending[1].clientEventId })
                assertNull(f.db.syncEventOutboxDao().getById(pending[1].id))
                val retained = requireNotNull(f.db.syncEventOutboxDao().getById(corrupted.id))
                assertEquals(corrupted.copy(lastErrorType = "invalid_persisted_entity_ids"), retained)
                assertEquals(1, f.db.syncEventOutboxDao().countAll())
                before.filterKeys { it != "sync_event_outbox" }.forEach { (table, raw) -> assertEquals(raw, f.snapshot(table)) }
                f.assertUnrelatedDomainsUntouched()
                expected = f.snapshots(); ledgerAfter = f.backend.records()
            }
            T11Fixture(T11OwnedContext(context.baseContext, context.runId, context.scenario, fresh = false)).use { reopened ->
                assertEquals(expected, reopened.snapshots()); assertEquals(ledgerAfter, reopened.backend.records())
                reopened.qualify(); assertEquals(expected, reopened.snapshots())
                reopened.saveEvidence("verified.json", reopened.evidence("malformed head retained; independent event ACK"))
            }
        }
    }

    @Test
    fun corruptedScopeOrSealInActualPersistedAttemptNeverSendsOrAcknowledges(): Unit = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            for (scenario in listOf("scope", "seal")) {
                val context = t11Context(scenario, fresh = true)
                var expected = emptyMap<String, String>()
                var originalLedger = emptyList<JsonObject>()
                T11Fixture(context).use { f ->
                    f.seed()
                    val product = f.addEditedProduct("T11-NATIVE-$scenario", "Actual sealed body")
                    val refBefore = requireNotNull(f.db.productRemoteRefDao().getByProductId(product.id))
                    assertTrue(refBefore.localChangeRevision > refBefore.lastSyncedLocalRevision)
                    assertNull(refBefore.lastRemoteAppliedAt)
                    f.backend.failBusiness = true
                    assertTrue(f.push().exceptionOrNull() is IOException)
                    val receipt = f.db.syncEventOutboxDao().listPending(T11Contract.OWNER, 10).single()
                    assertEquals(BUSINESS_WRITE_EVENT, receipt.eventType); assertEquals("PRODUCTS", receipt.domain)
                    assertEquals(1, receipt.attemptCount)
                    assertEquals("business_write_response_unconfirmed", receipt.lastErrorType)
                    val attempt = T11Contract.json.decodeFromString<BusinessWriteAttempt>(receipt.metadataJson)
                    validateBusinessWritePayload(attempt)
                    assertEquals(T11Contract.OWNER, attempt.owner)
                    assertEquals(refBefore.remoteId, attempt.payload.products.single().id)
                    assertEquals("Actual sealed body", attempt.payload.products.single().productName)
                    assertEquals("business-write-v1:" + nativeSha(receipt.metadataJson.encodeToByteArray()), receipt.clientEventId)
                    assertEquals(refBefore, f.db.productRemoteRefDao().getByProductId(product.id))
                    assertEquals(listOf("BUSINESS_ENTRY", "BUSINESS_ATTEMPT"), f.backend.records().map { it.getValue("kind").jsonPrimitive.content })
                    assertEquals(receipt.metadataJson, f.backend.records().filterKind("BUSINESS_ATTEMPT").single().getValue("rawAttempt").jsonPrimitive.content)
                    f.saveEvidence("corruption-preimage.json", f.evidence())
                    val changed = if (scenario == "scope") attempt.copy(owner = "14400000-0000-4000-8000-000000000099")
                    else attempt.copy(payload = attempt.payload.copy(products = listOf(attempt.payload.products.single().copy(productName = "Corrupted sealed body"))))
                    val corrupted = receipt.copy(metadataJson = T11Contract.json.encodeToString(changed))
                    assertNotEquals(receipt.metadataJson, corrupted.metadataJson)
                    assertEquals(receipt.clientEventId, corrupted.clientEventId)
                    f.db.syncEventOutboxDao().update(corrupted)
                    assertEquals(corrupted, f.db.syncEventOutboxDao().getById(receipt.id))
                    f.saveEvidence("corruption-readback.json", f.evidence())
                    expected = f.snapshots(); originalLedger = f.backend.records()
                }
                // Reopen the actual damaged predecessor before the protected replay, without reseed.
                T11Fixture(T11OwnedContext(context.baseContext, context.runId, scenario, fresh = false)).use { reopened ->
                    assertEquals(expected, reopened.snapshots()); assertEquals(originalLedger, reopened.backend.records())
                    reopened.qualify()
                    val result = reopened.push()
                    reopened.saveEvidence("exercise-observation.json", reopened.evidence(result.exceptionOrNull()?.message))
                    val failure = result.exceptionOrNull()
                    assertTrue(failure is IllegalArgumentException)
                    assertEquals(if (scenario == "scope") "business_write_scope_mismatch" else "business_write_receipt_mismatch", failure?.message)
                    assertEquals("No new business OR notification entry, not merely no extra remote row", originalLedger, reopened.backend.records())
                    assertEquals(expected, reopened.snapshots())
                    reopened.assertUnrelatedDomainsUntouched()
                }
                T11Fixture(T11OwnedContext(context.baseContext, context.runId, scenario, fresh = false)).use { reopened ->
                    assertEquals(expected, reopened.snapshots()); assertEquals(originalLedger, reopened.backend.records())
                    reopened.saveEvidence("verified.json", reopened.evidence("$scenario rejected before any new mutating transport entry; no ACK"))
                }
            }
        }
    }
}

private fun List<JsonObject>.filterKind(kind: String) = filter { it["kind"]?.jsonPrimitive?.content == kind }

/** Frozen R/G observation: no push, Room write, ledger append or reader implementation here. */
private suspend fun assertT11ReaderTracksAcknowledgedQ(f: T11Fixture, qId: String) {
    val localBefore = f.snapshots()
    val ledgerFile = File(f.context.root, "per-item-ledger.json")
    val ledgerBytes = ledgerFile.readBytes()
    val records = f.backend.records()
    val baseline = requireNotNull(f.db.syncRecoveryBaselineDao().get())
    val baselineCheckpoint = decodeRecoveryCheckpointJson(baseline.checkpointJson)
    val watermarkBefore = f.db.syncEventWatermarkDao().get(T11Contract.OWNER, T11Contract.scope.storeId)
    val journalBefore = f.db.syncRecoveryJournalDao().get()
    val afterId = watermarkBefore?.lastSyncEventId ?: 0L // Same read-only fallback as the real drain.
    val request = ShopSyncRpcContext(T11Contract.OWNER, T11Contract.SHOP, T11Contract.DEVICE,
        expectedScope = baselineCheckpoint.scope, verifiedBaselineId = afterId.toString())

    // This guard is deliberately for the one accepted Q in this selector, not a remote adapter.
    assertEquals(listOf("BUSINESS_ENTRY", "BUSINESS_ATTEMPT", "BUSINESS_ACK", "EVENT_ATTEMPT", "EVENT_ACK"),
        records.map { it.getValue("kind").jsonPrimitive.content })
    val accepted = records[1]
    val rawAttempt = accepted.getValue("rawAttempt").jsonPrimitive.content
    val attempt = T11Contract.json.decodeFromString<BusinessWriteAttempt>(rawAttempt)
    validateBusinessWritePayload(attempt)
    assertEquals("business-write-v1:" + nativeSha(rawAttempt.encodeToByteArray()), accepted.getValue("operationKey").jsonPrimitive.content)
    assertEquals(accepted.getValue("operationKey"), records[2].getValue("operationKey"))
    assertEquals(accepted.getValue("ids"), records[2].getValue("ids"))
    assertEquals(records[0].getValue("ids"), accepted.getValue("ids"))
    assertEquals(records[0].getValue("wireRows"), accepted.getValue("wireRows"))
    assertEquals(JsonArray(listOf(JsonPrimitive(qId))), accepted.getValue("ids"))
    assertEquals("PRODUCTS", attempt.payload.kind)
    assertEquals(T11Contract.OWNER, attempt.owner); assertEquals(T11Contract.SHOP, attempt.shop)
    assertEquals(T11Contract.scope.storeId, attempt.store); assertEquals(T11Contract.DEVICE, attempt.device)
    assertEquals(baseline.generationId, attempt.generation)
    assertEquals(T11Contract.json.encodeToJsonElement(attempt.payload.products), accepted.getValue("wireRows"))
    val rawQ = attempt.payload.products.single()
    assertEquals(qId, rawQ.id); assertEquals(T11Contract.OWNER, rawQ.ownerUserId); assertEquals(T11Contract.SHOP, rawQ.shopId)
    assertNull(rawQ.supplierId); assertNull(rawQ.categoryId); assertNull(rawQ.deletedAt)
    val recordedAtMs = accepted.getValue("atMs").jsonPrimitive.long
    assertTrue(recordedAtMs > 0L)
    // Only the synthetic read timestamp is normalized; the sealed payload and ledger stay exact.
    val expectedQ = rawQ.copy(updatedAt = rawQ.updatedAt ?: DateTimeFormatter
        .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochMilli(recordedAtMs)))
    val eventAck = records[4]
    assertEquals(records[3].getValue("clientEventId"), eventAck.getValue("clientEventId"))
    assertEquals(records[3].getValue("rawParams"), eventAck.getValue("rawParams"))
    val params = T11Contract.json.decodeFromString<SyncEventRecordRpcParams>(eventAck.getValue("rawParams").jsonPrimitive.content)
    val rawEvent = eventAck.getValue("rawRow").jsonPrimitive.content
    val event = T11Contract.json.decodeFromString<SyncEventRemoteRow>(rawEvent)
    assertEquals(T11Contract.OWNER, event.ownerUserId); assertEquals(T11Contract.SHOP, event.shopId)
    assertEquals(T11Contract.DEVICE, event.sourceDeviceId); assertEquals(params.storeId, event.storeId)
    assertEquals(params.clientEventId, event.clientEventId); assertEquals(params.domain, event.domain)
    assertEquals(params.eventType, event.eventType); assertEquals(params.source, event.source)
    assertEquals(params.batchId, event.batchId); assertEquals(params.changedCount, event.changedCount)
    assertEquals(params.entityIds, event.entityIds)
    assertEquals(SyncEventDomains.CATALOG, event.domain)
    assertEquals(SyncEventEntityIds(productIds = listOf(qId)), event.entityIds)
    assertTrue(event.id > afterId)
    val expectedMax = event.id.toString()
    assertEquals(afterId.toString(), baselineCheckpoint.syncEvents.maxId)
    assertEquals(setOf(SyncEventDomains.CATALOG, SyncEventDomains.PRICES, SyncEventDomains.HISTORY),
        baselineCheckpoint.syncEvents.domainMaxIds.keys)
    val expectedFences = baselineCheckpoint.syncEvents.domainMaxIds + (event.domain to expectedMax)
    val expectedRows: Map<ShopSyncRowDomain, ShopSyncRows> = mapOf(
        ShopSyncRowDomain.SUPPLIERS to ShopSyncRows.Suppliers(emptyList()),
        ShopSyncRowDomain.CATEGORIES to ShopSyncRows.Categories(emptyList()),
        ShopSyncRowDomain.PRODUCTS to ShopSyncRows.Products(listOf(expectedQ)),
        ShopSyncRowDomain.PRICES to ShopSyncRows.Prices(emptyList()),
        ShopSyncRowDomain.HISTORY to ShopSyncRows.History(emptyList()),
        ShopSyncRowDomain.IMAGES to ShopSyncRows.Images(emptyList()))
    val expectedDomains = expectedRows.mapValues { (domain, rows) -> t11ObservedDomain(rows, domain, baseline.generationId) }
    val expectedCatalog = ShopSyncCatalogCheckpoint(expectedDomains.getValue(ShopSyncRowDomain.SUPPLIERS),
        expectedDomains.getValue(ShopSyncRowDomain.CATEGORIES), expectedDomains.getValue(ShopSyncRowDomain.PRODUCTS),
        nativeSha(listOf(ShopSyncRowDomain.SUPPLIERS, ShopSyncRowDomain.CATEGORIES, ShopSyncRowDomain.PRODUCTS)
            .joinToString("\n") { expectedDomains.getValue(it).versionDigest }.encodeToByteArray()))

    suspend fun assertReadOnly() {
        assertEquals("Reader changed local tables", localBefore, f.snapshots())
        assertEquals("Reader changed inbound watermark", watermarkBefore,
            f.db.syncEventWatermarkDao().get(T11Contract.OWNER, T11Contract.scope.storeId))
        assertEquals("Reader changed recovery journal", journalBefore, f.db.syncRecoveryJournalDao().get())
        assertArrayEquals("Reader changed the durable mutating ledger", ledgerBytes, ledgerFile.readBytes())
    }
    suspend fun <T> observe(name: String, read: T11ObservedRead, encode: (T) -> JsonElement,
        call: suspend (T11ObservedRead) -> Result<T>): Result<T> {
        val result = try { call(read) } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            Result.failure(failure)
        }
        // Persist the actual response/failure before any response assertion, including the expected RED.
        f.saveEvidence("reader-$name.json", buildJsonObject {
            put("protocol", T11Contract.PROTOCOL); put("runId", f.context.runId); put("scenario", f.context.scenario)
            put("expectedMax", expectedMax); put("expectedFences", buildJsonObject { expectedFences.forEach { (k, v) -> put(k, v) } })
            put("ledgerSha256", nativeSha(ledgerBytes)); put("expectedQ", T11Contract.json.encodeToJsonElement(expectedQ))
            put("acceptedAttemptAtMs", recordedAtMs); put("expectedCatalog", T11Contract.json.encodeToJsonElement(expectedCatalog))
            put("request", read.evidence())
            put("actual", result.getOrNull()?.let(encode) ?: JsonNull)
            put("failureClass", result.exceptionOrNull()?.javaClass?.name); put("failureMessage", result.exceptionOrNull()?.message)
        })
        assertReadOnly()
        return result
    }
    var primary: Throwable? = null
    try {
        val checkpoint = observe<ShopSyncRecoveryCheckpoint>("checkpoint", T11ObservedRead("checkpoint", request),
            { T11Contract.json.encodeToJsonElement(it) }) { read -> f.reader.checkpoint(read.context) }.getOrThrow()
        // First response oracle: old reader0 cannot describe the exact durable ACK1 above.
        assertEquals("T11_READER_ACK_CHECKPOINT_MAX", expectedMax, checkpoint.syncEvents.maxId)
        assertEquals("shop-sync-recovery-checkpoint-v1", checkpoint.schemaVersion); assertEquals("ready", checkpoint.status)
        assertEquals(T11Contract.SHOP, checkpoint.shopId); assertEquals(request.expectedScope, checkpoint.scope)
        assertEquals(request.verifiedBaselineId, checkpoint.syncEvents.verifiedBaselineId)
        assertFalse(checkpoint.syncEvents.requiresFullRecovery)
        assertEquals(expectedFences, checkpoint.syncEvents.domainMaxIds)
        assertNull(checkpoint.syncEvents.oldestBlockingId); assertNull(checkpoint.syncEvents.newestBlockingId)
        assertEquals(expectedCatalog, checkpoint.catalog)
        assertEquals(expectedDomains.getValue(ShopSyncRowDomain.PRICES), checkpoint.prices)
        assertEquals(expectedDomains.getValue(ShopSyncRowDomain.HISTORY), checkpoint.history)
        assertEquals(expectedDomains.getValue(ShopSyncRowDomain.IMAGES), checkpoint.images)
        assertEquals(ShopSyncIntegrityCheckpoint(0, 0, 0, 0, 0, 0), checkpoint.integrity)
        assertTrue("Opaque checkpoint digest format", checkpoint.checkpointDigest.matches(Regex("[0-9a-f]{64}")))
        val captured = request.copy(expectedEventMaxId = checkpoint.syncEvents.maxId)
        for ((domain, expectedBody) in expectedRows) {
            val domainFence = expectedFences.getValue(domain.syncEventDomain())
            val pageRequest = captured.copy(expectedDomainEventMaxId = domainFence)
            val page = observe("body-${domain.wireValue}",
                T11ObservedRead("recoveryPage", pageRequest, domain = domain, rowAfterId = null, limit = 1), ::t11ObservedPage) { read ->
                f.reader.recoveryPage(read.context, requireNotNull(read.domain), read.rowAfterId, requireNotNull(read.limit))
            }.getOrThrow()
            assertEquals("shop-sync-recovery-page-v1", page.schemaVersion); assertEquals(T11Contract.SHOP, page.shopId)
            assertEquals(checkpoint.scope, page.scope); assertEquals(domain, page.domain)
            assertEquals(expectedMax, page.snapshotEventMaxId); assertEquals(expectedMax, page.currentScopeEventMaxId)
            assertEquals(domainFence, page.baselineDomainEventMaxId); assertEquals(domainFence, page.pageDomainEventMaxId)
            assertEquals(ShopSyncScopeKinds.SHOP_SCOPED, page.domainScope); assertEquals(1, page.pageLimit)
            assertEquals(expectedBody, page.rows); assertFalse(page.hasMore); assertNull(page.nextAfterId)
            assertTrue(page.responseBytes > 0L)
            assertEquals(expectedDomains.getValue(domain), t11ObservedDomain(page.rows, domain, baseline.generationId))
        }
        val tail = observe("tail", T11ObservedRead("eventPage", captured, eventAfterId = afterId, limit = 1),
            ::t11ObservedTail) { read -> f.reader.eventPage(read.context, requireNotNull(read.eventAfterId), requireNotNull(read.limit)) }.getOrThrow()
        validateTailPage(tail, checkpoint, T11Contract.SHOP, afterId, pageLimit = 1)
        validateTailEvent(event, checkpoint, T11Contract.SHOP)
        assertEquals(expectedMax, tail.scopeEventMaxId); assertEquals(listOf(event), tail.rows)
        assertEquals(rawEvent, T11Contract.json.encodeToString(tail.rows.single()))
        assertFalse(tail.hasMore); assertNull(tail.nextAfterId); assertTrue(tail.responseBytes > 0L)
        val terminal = observe("tail-terminal", T11ObservedRead("eventPage", captured, eventAfterId = event.id, limit = 1),
            ::t11ObservedTail) { read -> f.reader.eventPage(read.context, requireNotNull(read.eventAfterId), requireNotNull(read.limit)) }.getOrThrow()
        validateTailPage(terminal, checkpoint, T11Contract.SHOP, event.id, pageLimit = 1)
        assertEquals(expectedMax, terminal.scopeEventMaxId); assertTrue(terminal.rows.isEmpty())
        assertFalse(terminal.hasMore); assertNull(terminal.nextAfterId); assertTrue(terminal.responseBytes > 0L)
        val repeated = observe<ShopSyncRecoveryCheckpoint>("checkpoint-repeat", T11ObservedRead("checkpoint", request),
            { T11Contract.json.encodeToJsonElement(it) }) { read -> f.reader.checkpoint(read.context) }.getOrThrow()
        assertEquals(checkpoint, repeated)
        val marker = observe<ShopSyncConvergenceMarker>("marker", T11ObservedRead("convergenceMarker", request),
            { T11Contract.json.encodeToJsonElement(it) }) { read -> f.reader.convergenceMarker(read.context) }.getOrThrow()
        assertEquals("shop-sync-convergence-marker-v1", marker.schemaVersion); assertEquals("ready", marker.status)
        assertEquals(checkpoint.shopId, marker.shopId); assertEquals(checkpoint.scope, marker.scope)
        assertEquals(checkpoint.syncEvents.maxId, marker.syncEvents.maxId)
        assertEquals(checkpoint.syncEvents.domainMaxIds, marker.syncEvents.domainMaxIds)
        assertEquals(request.verifiedBaselineId, marker.syncEvents.verifiedBaselineId)
        assertFalse(marker.syncEvents.requiresFullRecovery); assertFalse(marker.serverNoWorkEligible)
        assertEquals(checkpoint.catalog, marker.catalog)
        assertEquals(checkpoint.prices, marker.prices); assertEquals(checkpoint.history, marker.history); assertEquals(checkpoint.images, marker.images)
        assertEquals(0L, marker.integrity.totalViolationCount)
        assertEquals("Opaque digest consistency, not a reconstructed SQL proof", checkpoint.checkpointDigest, marker.checkpointDigest)

        // Observational refusal probes only: no new write/identity and no replacement authority.
        val foreign = "14400000-0000-4000-8000-000000000099"
        for ((name, denied) in listOf("owner" to request.copy(accountId = foreign),
            "shop" to request.copy(shopId = foreign), "device" to request.copy(deviceIdentifier = foreign))) {
            val refused = observe<ShopSyncRecoveryCheckpoint>("foreign-$name", T11ObservedRead("checkpoint", denied),
                { T11Contract.json.encodeToJsonElement(it) }) { read -> f.reader.checkpoint(read.context) }
            assertTrue("Reader accepted foreign $name", refused.isFailure)
            val deniedCaptured = denied.copy(expectedEventMaxId = captured.expectedEventMaxId)
            assertTrue("Reader returned tail to foreign $name", observe("foreign-$name-tail",
                T11ObservedRead("eventPage", deniedCaptured, eventAfterId = afterId, limit = 1), ::t11ObservedTail) { read ->
                f.reader.eventPage(read.context, requireNotNull(read.eventAfterId), requireNotNull(read.limit))
            }.isFailure)
            val deniedProduct = deniedCaptured.copy(expectedDomainEventMaxId = checkpoint.syncEvents.domainMaxIds.getValue(SyncEventDomains.CATALOG))
            assertTrue("Reader returned product body to foreign $name", observe("foreign-$name-product",
                T11ObservedRead("recoveryPage", deniedProduct, domain = ShopSyncRowDomain.PRODUCTS, rowAfterId = null, limit = 1), ::t11ObservedPage) { read ->
                f.reader.recoveryPage(read.context, requireNotNull(read.domain), read.rowAfterId, requireNotNull(read.limit))
            }.isFailure)
        }
        val wrongFence = captured.copy(expectedEventMaxId = (event.id + 1L).toString())
        assertTrue("Reader accepted an uncaptured event fence", observe("wrong-event-fence",
            T11ObservedRead("eventPage", wrongFence, eventAfterId = afterId, limit = 1), ::t11ObservedTail) { read ->
            f.reader.eventPage(read.context, requireNotNull(read.eventAfterId), requireNotNull(read.limit))
        }.isFailure)
        val wrongDomainFence = captured.copy(expectedDomainEventMaxId = (event.id + 1L).toString())
        assertTrue("Reader accepted an uncaptured domain fence", observe("wrong-domain-fence",
            T11ObservedRead("recoveryPage", wrongDomainFence, domain = ShopSyncRowDomain.PRODUCTS, rowAfterId = null, limit = 1), ::t11ObservedPage) { read ->
            f.reader.recoveryPage(read.context, requireNotNull(read.domain), read.rowAfterId, requireNotNull(read.limit))
        }.isFailure)
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        try { assertReadOnly() } catch (preservationFailure: Throwable) {
            val original = primary
            if (original == null) throw preservationFailure else original.addSuppressed(preservationFailure)
        }
    }
}

/** One immutable argument set feeds both the actual reader call and its observation receipt. */
private data class T11ObservedRead(
    val endpoint: String,
    val context: ShopSyncRpcContext,
    val domain: ShopSyncRowDomain? = null,
    val rowAfterId: String? = null,
    val eventAfterId: Long? = null,
    val limit: Int? = null
) {
    fun evidence(): JsonObject = buildJsonObject {
        put("endpoint", endpoint)
        put("context", buildJsonObject {
            put("accountId", context.accountId); put("shopId", context.shopId); put("deviceIdentifier", context.deviceIdentifier)
            put("expectedScope", context.expectedScope?.let { T11Contract.json.encodeToJsonElement(it) } ?: JsonNull)
            put("verifiedBaselineId", context.verifiedBaselineId); put("expectedBaselineScopeKey", context.expectedBaselineScopeKey)
            put("expectedEventMaxId", context.expectedEventMaxId); put("expectedDomainEventMaxId", context.expectedDomainEventMaxId)
        })
        when (endpoint) {
            "recoveryPage" -> {
                put("domain", requireNotNull(domain).wireValue); put("afterId", rowAfterId); put("limit", requireNotNull(limit))
            }
            "eventPage" -> { put("afterId", requireNotNull(eventAfterId)); put("limit", requireNotNull(limit)) }
        }
    }
}

private fun t11ObservedDomain(rows: ShopSyncRows, domain: ShopSyncRowDomain, generation: String): ShopSyncDomainCheckpoint {
    val manifests = rows.toManifestRows(generation, domain).sortedBy { it.remoteId }
    return ShopSyncDomainCheckpoint(manifests.count { it.active }.toLong(), manifests.count { !it.active }.toLong(),
        shopSyncCheckpointChainDigest(manifests.map { it.idLine }), shopSyncCheckpointChainDigest(manifests.map { it.versionLine }),
        shopSyncCheckpointChainDigest(manifests.mapNotNull { it.identityLine }).takeIf { domain == ShopSyncRowDomain.PRODUCTS })
}

private fun t11ObservedPage(page: ShopSyncRecoveryPage): JsonObject = buildJsonObject {
    put("schemaVersion", page.schemaVersion); put("shopId", page.shopId); put("scope", T11Contract.json.encodeToJsonElement(page.scope))
    put("domain", page.domain.wireValue); put("snapshotEventMaxId", page.snapshotEventMaxId)
    put("currentScopeEventMaxId", page.currentScopeEventMaxId); put("baselineDomainEventMaxId", page.baselineDomainEventMaxId)
    put("pageDomainEventMaxId", page.pageDomainEventMaxId); put("domainScope", page.domainScope); put("pageLimit", page.pageLimit)
    put("nextAfterId", page.nextAfterId); put("hasMore", page.hasMore); put("responseBytes", page.responseBytes)
    put("rows", when (val rows = page.rows) {
        is ShopSyncRows.Suppliers -> T11Contract.json.encodeToJsonElement(rows.values)
        is ShopSyncRows.Categories -> T11Contract.json.encodeToJsonElement(rows.values)
        is ShopSyncRows.Products -> T11Contract.json.encodeToJsonElement(rows.values)
        is ShopSyncRows.Prices -> T11Contract.json.encodeToJsonElement(rows.values)
        is ShopSyncRows.History -> T11Contract.json.encodeToJsonElement(rows.values)
        is ShopSyncRows.Images -> T11Contract.json.encodeToJsonElement(rows.values)
    })
}

private fun t11ObservedTail(page: ShopSyncEventPage): JsonObject = buildJsonObject {
    put("schemaVersion", page.schemaVersion); put("shopId", page.shopId); put("scope", T11Contract.json.encodeToJsonElement(page.scope))
    put("scopeEventMaxId", page.scopeEventMaxId); put("asOfEventMaxId", page.asOfEventMaxId)
    put("asOfDomainEventMaxIds", buildJsonObject { page.asOfDomainEventMaxIds.forEach { (k, v) -> put(k, v) } })
    put("pageLimit", page.pageLimit); put("nextAfterId", page.nextAfterId); put("hasMore", page.hasMore)
    put("responseBytes", page.responseBytes); put("rows", T11Contract.json.encodeToJsonElement(page.rows))
}
