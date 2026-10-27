package com.example.merchandisecontrolsplitview.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlinx.serialization.json.*

/** Observation contract only. No SDK/session/database/TEST qualification is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class, sdk=[33])
class PilotB6TraceTest {
    private val h="a".repeat(64)
    private val remote="00000000-0000-4000-8000-000000000144"
    private fun binding(peer:Boolean=false)=PilotCommitTrace.B6Binding(h,h,
        if(peer) "T20-G01-05" else "T20-G02-04",UUID.randomUUID().toString(),UUID.randomUUID().toString(),
        if(peer) "peer" else "source","update",scope,h,h,listOf(PilotCommitTrace.hash(remote)),7L,h)
    private fun arm(b:PilotCommitTrace.B6Binding) = requireNotNull(PilotCommitTrace.b6ArmFromTestOnly(b,scope,h,h,h,authority()))
    @After fun disarm() { PilotCommitTrace.b6SnapshotFromTestOnly(disarm=true) }

    private fun causalBinding(peer:Boolean=false)=binding(peer).copy(
        timingMethod=PilotCommitTrace.B6_CAUSAL_METHOD,
        sourceAdmissionNonce=if(peer) null else UUID.randomUUID().toString(),
        visibilityConfirmationNonce=if(peer) UUID.randomUUID().toString() else null)
    private fun advanceCausalClock()=org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1))
    private fun sourceAction(b:PilotCommitTrace.B6Binding,ns:Long=PilotCommitTrace.now()) {
        val announced=PilotCommitTrace.b6AnnounceSourceUIActionFromTestOnly(ns)
        PilotCommitTrace.b6SourceUIActionFromTestOnly(b.category,b.localProductID,ns,"product-editor-save",announced)
    }

    @Test fun causalIdleWitnessReadsRealEmptyStateAndPinsItsExactPrefix() {
        val b=causalBinding();arm(b)
        val witness=requireNotNull(PilotCommitTrace.b6SourceIdleWitnessFromTestOnly(requireNotNull(b.sourceAdmissionNonce)))
        val state=witness.getValue("source_idle_state").jsonObject
        assertEquals(setOf("trigger_count","mutation_ticket_count","transaction_count","seal_count","source_result_count"),state.keys)
        assertTrue(state.values.all { it.jsonPrimitive.int==0 })
        val prefix=witness.getValue("trace_prefix_raw").jsonPrimitive.content
        assertEquals(PilotCommitTrace.hash(prefix),witness.getValue("trace_prefix_sha256").jsonPrimitive.content)
        val events=requireNotNull(PilotCommitTrace.b6SnapshotFromTestOnly()).events
        assertEquals(listOf("causal_source_idle_witness"),events.map { it.stage })
        assertEquals(0,events.single().causalSequence)
        assertEquals(json.parseToJsonElement(prefix),json.parseToJsonElement(json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PilotCommitTrace.B6Event.serializer()),events)))
    }

    @Test fun alreadyAnnouncedUIEntryCannotBeRelabelledIdleWhileItsCallbackWaitsForTraceLock() {
        val b=causalBinding();arm(b)
        val announced=java.util.concurrent.CountDownLatch(1)
        val problem=java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        lateinit var worker:Thread
        synchronized(PilotCommitTrace) {
            worker=Thread {
                try {
                    val ns=PilotCommitTrace.now()
                    val ticket=PilotCommitTrace.b6AnnounceSourceUIActionFromTestOnly(ns)
                    announced.countDown()
                    PilotCommitTrace.b6SourceUIActionFromTestOnly("update",7,ns,"product-editor-save",ticket)
                } catch(error:Throwable) { problem.set(error) }
            }
            worker.start()
            assertTrue(announced.await(1,java.util.concurrent.TimeUnit.SECONDS))
            assertNull(PilotCommitTrace.b6SourceIdleWitnessFromTestOnly(requireNotNull(b.sourceAdmissionNonce)))
        }
        worker.join(1_000);assertFalse(worker.isAlive);assertNull(problem.get())
        assertNotNull(PilotCommitTrace.b6SnapshotFromTestOnly()?.invalidReason)
        assertNull(PilotCommitTrace.b6CausalTracePreviewFromTestOnly())
    }

    @Test fun earlierCapturedUIHookAppendedAfterWitnessKeepsOldTimestampAndFails() {
        val b=causalBinding();arm(b);val early=PilotCommitTrace.now();advanceCausalClock()
        val witness=requireNotNull(PilotCommitTrace.b6SourceIdleWitnessFromTestOnly(requireNotNull(b.sourceAdmissionNonce)))
        assertTrue(witness.getValue("native_ns").jsonPrimitive.long>early)
        advanceCausalClock();sourceAction(b,early)
        val receipt=requireNotNull(PilotCommitTrace.b6SnapshotFromTestOnly())
        val action=receipt.events.single { it.stage=="source_ui_action_begin" }
        assertEquals(early,action.ns);assertEquals(early,action.causalEarliestHookNS)
        assertNotNull(receipt.invalidReason)
    }

    @Test fun sourceHookBeforeWitnessCannotCreateAnIdleProof() {
        val b=causalBinding();arm(b)
        PilotCommitTrace.b6BeginLocal(7,scope,lease=localLease)
        assertNull(PilotCommitTrace.b6SourceIdleWitnessFromTestOnly(requireNotNull(b.sourceAdmissionNonce)))
        assertNotNull(PilotCommitTrace.b6SnapshotFromTestOnly()?.invalidReason)
    }

    @Test fun sourceWitnessPrefixExtendsWithOneRealUIEntryAndStrictLaterSourceBoundaries() {
        val b=causalBinding();arm(b)
        val witness=requireNotNull(PilotCommitTrace.b6SourceIdleWitnessFromTestOnly(requireNotNull(b.sourceAdmissionNonce)))
        val witnessNS=witness.getValue("native_ns").jsonPrimitive.long;advanceCausalClock();sourceAction(b)
        val ticket=PilotCommitTrace.b6BeginLocal(7,scope,lease=localLease)
        PilotCommitTrace.b6LocalIntent(7,remote,4)
        val committed=PilotCommitTrace.now()
        PilotCommitTrace.b6LocalEnd(ticket,true,h,returnedNS=committed,lease=localLease)
        val receipt=requireNotNull(PilotCommitTrace.b6SnapshotFromTestOnly())
        assertNull(receipt.invalidReason)
        assertEquals(receipt.events.indices.toList(),receipt.events.map { it.causalSequence })
        assertEquals(1,receipt.events.count { it.stage=="source_ui_action_begin" })
        assertTrue(receipt.events.drop(1).all { val earliest=requireNotNull(it.causalEarliestHookNS); earliest>witnessNS && earliest<=it.ns })
        assertEquals(committed,receipt.events.last().ns)
        assertNull("Local commit alone is not an actual own ACK",PilotCommitTrace.b6CausalTracePreviewFromTestOnly())
    }

    @Test fun duplicateUIEntryNeverBecomesASecondAcceptedOrdinarySave() {
        val b=causalBinding();arm(b)
        requireNotNull(PilotCommitTrace.b6SourceIdleWitnessFromTestOnly(requireNotNull(b.sourceAdmissionNonce)))
        advanceCausalClock();sourceAction(b);advanceCausalClock();sourceAction(b)
        val receipt=requireNotNull(PilotCommitTrace.b6SnapshotFromTestOnly())
        assertNotNull(receipt.invalidReason);assertEquals(1,receipt.events.count { it.stage=="source_ui_action_begin" })
        assertNull(PilotCommitTrace.b6CausalTracePreviewFromTestOnly())
    }

    @Test fun causalPeerPreviewRequiresActualCommittedBodyAndLaterDrawAndRemainsAnImmutablePrefix()=runBlocking {
        val b=causalBinding(peer=true);arm(b);assertNull(PilotCommitTrace.b6CausalTracePreviewFromTestOnly())
        var preview:JsonObject?=null
        observeCycle({ scope },"foreground_interval") {
            val cycle=kotlin.coroutines.coroutineContext[PilotCommitTrace.B6CycleContext]?.ticket
            val ticket=PilotCommitTrace.b6BeginApply(cycle,scope,listOf(remote),lease=cloudLease)
            PilotCommitTrace.b6AppliedProductFacts(cycle,remote,7,0,h,"2026-10-23T12:34:56Z")
            assertNull(PilotCommitTrace.b6CausalTracePreviewFromTestOnly())
            PilotCommitTrace.b6ApplyEnd(ticket,lease=cloudLease)
            assertNull(PilotCommitTrace.b6CausalTracePreviewFromTestOnly())
            PilotCommitTrace.b6Rendered(7,"b6:${b.operationID}:$h",PilotCommitTrace.now())
            preview=requireNotNull(PilotCommitTrace.b6CausalTracePreviewFromTestOnly())
        }
        val frozen=requireNotNull(preview);assertEquals(frozen,PilotCommitTrace.b6CausalTracePreviewFromTestOnly())
        val prefix=frozen.getValue("trace_prefix_raw").jsonPrimitive.content
        val final=requireNotNull(PilotCommitTrace.b6SnapshotFromTestOnly()).events
        assertEquals(json.parseToJsonElement(prefix),json.parseToJsonElement(json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PilotCommitTrace.B6Event.serializer()),
            final.take(frozen.getValue("trace_event_count").jsonPrimitive.int))))
        assertFalse(frozen.getValue("receipt_raw").jsonPrimitive.content.contains("nativeAcceptance\":true"))
    }

    @Test fun unknownMethodAndCalibrationNonceReuseCannotArmCausalObservation() {
        val b=causalBinding()
        assertNull(PilotCommitTrace.b6ArmFromTestOnly(b.copy(timingMethod="UNKNOWN"),scope,h,h,h,authority()))
        assertNull(PilotCommitTrace.b6ArmFromTestOnly(b.copy(sourceAdmissionNonce=b.nonce),scope,h,h,h,authority()))
    }

    @Test fun legacyReceiptSerializationDoesNotAcquireCausalFieldsOrEvents() {
        arm(binding());val ticket=PilotCommitTrace.b6BeginLocal(7,scope,lease=localLease)
        PilotCommitTrace.b6LocalIntent(7,remote,4);PilotCommitTrace.b6LocalEnd(ticket,true,h,lease=localLease)
        val receipt=requireNotNull(PilotCommitTrace.b6SnapshotFromTestOnly())
        val raw=json.encodeToString(PilotCommitTrace.B6Receipt.serializer(),receipt)
        listOf("timingMethod","sourceAdmissionNonce","visibilityConfirmationNonce","causalSequence","causalEarliestHookNS").forEach { assertFalse(it,raw.contains(it)) }
        assertEquals(listOf("local_transaction_begin","local_intent_staged","local_commit_return"),receipt.events.map { it.stage })
    }

    @Test fun causalHistoryUIEntryUsesActualHistoryUIDForBothRenameHandlers() {
        for(handler in listOf("history-rename-confirm","generated-rename-confirm")) {
            val b=causalBinding().copy(sampleID="T20-G02-02",category="history",localProductID=null,localEntityID=9L)
            arm(b)
            requireNotNull(PilotCommitTrace.b6SourceIdleWitnessFromTestOnly(requireNotNull(b.sourceAdmissionNonce)))
            advanceCausalClock();val ns=PilotCommitTrace.now()
            val announced=PilotCommitTrace.b6AnnounceSourceUIActionFromTestOnly(ns)
            PilotCommitTrace.b6SourceUIActionFromTestOnly("history",9L,ns,handler,announced)
            val receipt=requireNotNull(PilotCommitTrace.b6SnapshotFromTestOnly())
            assertNull(receipt.invalidReason)
            val entry=receipt.events.single { it.stage=="source_ui_action_begin" }
            assertEquals(9L,entry.localID);assertEquals("history:"+handler,entry.outcome)
            assertEquals(ns,entry.ns);assertEquals(ns,entry.causalEarliestHookNS)
            assertNull("UI entry alone cannot create an ACK preview",PilotCommitTrace.b6CausalTracePreviewFromTestOnly())
            disarm()
        }
    }

    @Test fun absentArmCannotObserveOrChangeBodyExecution()=runBlocking {
        disarm(); var called=false
        assertNull(PilotCommitTrace.b6BeginLocal(7,scope, lease=localLease))
        PilotCommitTrace.b6ObserveCycle({ error("OFF must not compute scope/hash") },"foreground_interval") { called=true }
        assertTrue(called)
    }
    @Test fun sourceBracketContainsIntentAndReturnButDoesNotGrantAcceptance() {
        val b=binding(); val reply=arm(b); val ticket=PilotCommitTrace.b6BeginLocal(7,scope, lease=localLease)
        assertNotNull(ticket); PilotCommitTrace.b6LocalIntent(7,remote,4)
        PilotCommitTrace.b6LocalEnd(ticket,true,h, lease=localLease)
        val receipt=requireNotNull(PilotCommitTrace.b6SnapshotFromTestOnly())
        assertEquals(listOf("local_transaction_begin","local_intent_staged","local_commit_return"),receipt.events.map { it.stage })
        assertEquals(4,receipt.events[1].revision); assertTrue(receipt.events.first().ns>=reply.remoteNS)
        assertTrue(receipt.events.last().ns>=receipt.events.first().ns); assertFalse(receipt.nativeAcceptance)
        assertNull(receipt.invalidReason)
    }
    @Test fun noopAndMissingIntentNeverQualifyAsDurableSave() {
        arm(binding()); val ticket=PilotCommitTrace.b6BeginLocal(7,scope, lease=localLease)
        PilotCommitTrace.b6LocalEnd(ticket,false,h, lease=localLease)
        assertEquals("LOCAL_COMMIT_FACTS_UNQUALIFIED",PilotCommitTrace.b6SnapshotFromTestOnly()?.invalidReason)
    }
    @Test fun thrownCommitRemainsUnconfirmedNotRollback() {
        arm(binding()); val ticket=PilotCommitTrace.b6BeginLocal(7,scope, lease=localLease)
        PilotCommitTrace.b6LocalEnd(ticket,null,null,failed=true, lease=localLease)
        assertEquals("UNCONFIRMED",PilotCommitTrace.b6SnapshotFromTestOnly()?.events?.last()?.outcome)
    }
    @Test fun wrongIdentityDoesNotAcquireObservationTicket() {
        val b=binding(); assertNull(PilotCommitTrace.b6ArmFromTestOnly(b,"b".repeat(64),h,h,h,authority()))
        arm(b); assertNull(PilotCommitTrace.b6BeginLocal(8,scope, lease=localLease)); assertNull(PilotCommitTrace.b6BeginLocal(7,"b".repeat(64), lease=localLease))
    }
    @Test fun peerApplyRequiresItsActualAdmittedCycleContext()=runBlocking {
        val b=binding(peer=true); arm(b)
        assertNull(PilotCommitTrace.b6BeginApply(null,scope,listOf(remote), lease=cloudLease))
        observeCycle({ scope },"foreground_interval") {
            val cycle=kotlin.coroutines.coroutineContext[PilotCommitTrace.B6CycleContext]?.ticket
            val apply=PilotCommitTrace.b6BeginApply(cycle,scope,listOf(remote), lease=cloudLease); assertNotNull(apply)
            PilotCommitTrace.b6AppliedProductFacts(cycle,remote,7,0,h,"2026-10-23T12:34:56.123456Z")
            PilotCommitTrace.b6ApplyEnd(apply, lease=cloudLease)
        }
        val events=requireNotNull(PilotCommitTrace.b6SnapshotFromTestOnly()).events
        assertEquals(listOf("receiver_admitted","peer_transaction_begin","peer_product_applied","peer_commit_return","receiver_cycle_end"),events.map { it.stage })
        assertEquals(1,events.mapNotNull { it.cycleID }.distinct().size)
    }
    @Test fun observerPreservesCancellationAndFlagsManualTrigger()=runBlocking {
        arm(binding(peer=true)); val original=kotlinx.coroutines.CancellationException("test")
        val error=runCatching { observeCycle({ scope },"foreground_interval") { throw original } }.exceptionOrNull()
        // Coroutine stack recovery may copy the exception, but must retain this exact original.
        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertEquals(original.javaClass,error?.javaClass)
        assertEquals(original.message,error?.message)
        assertSame(original,if(error === original) error else error?.cause)
        val ended=requireNotNull(PilotCommitTrace.b6SnapshotFromTestOnly()).events
        assertEquals(listOf("receiver_admitted","receiver_cycle_end"),ended.map { it.stage })
        assertNotNull(ended.first().cycleID)
        assertEquals(ended.first().cycleID,ended.last().cycleID)
        var ran=false; observeCycle({ scope },"manual") { ran=true }
        assertTrue(ran); assertEquals("NON_ORDINARY_RECEIVER_TRIGGER",PilotCommitTrace.b6SnapshotFromTestOnly()?.invalidReason)
    }
    @Test fun operationCannotBeRearmedAfterDisarm() {
        val b=binding(); arm(b); disarm()
        assertNull(PilotCommitTrace.b6ArmFromTestOnly(b,scope,h,h,h,authority()))
    }
    @Test fun expectedBodyAndRequiredTypedChildBindingsCannotBeOmitted() {
        assertNull(PilotCommitTrace.b6ArmFromTestOnly(binding().copy(expectedLocalBodyHash=null),scope,h,h,h,authority()))
        assertNull(PilotCommitTrace.b6ArmFromTestOnly(binding().copy(entityHashes=listOf(h,"b".repeat(64))),scope,h,h,h,authority()))
        assertNull(PilotCommitTrace.b6ArmFromTestOnly(binding().copy(sampleID="T20-G02-06",category="price"),scope,h,h,h,authority()))
    }
    @Test fun sameProductWrongSavedValuesCannotQualify() {
        arm(binding()); val ticket=PilotCommitTrace.b6BeginLocal(7,scope, lease=localLease)
        PilotCommitTrace.b6LocalIntent(7,remote,4)
        PilotCommitTrace.b6LocalEnd(ticket,true,"b".repeat(64), lease=localLease)
        assertEquals("LOCAL_COMMIT_FACTS_UNQUALIFIED",PilotCommitTrace.b6SnapshotFromTestOnly()?.invalidReason)
    }
    @Test fun missingTargetIDsCannotStartApply()=runBlocking {
        arm(binding(peer=true))
        observeCycle({ scope },"foreground_interval") {
            val cycle=kotlin.coroutines.coroutineContext[PilotCommitTrace.B6CycleContext]?.ticket
            assertNull(PilotCommitTrace.b6BeginApply(cycle,scope,listOf(UUID.randomUUID().toString()), lease=cloudLease))
        }
        assertTrue(PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.none { it.stage=="peer_commit_return" })
    }
    @Test fun returnedWindowWithoutActuallyAppliedTargetBodyCannotQualifyDraw()=runBlocking {
        val b=binding(peer=true); arm(b)
        observeCycle({ scope },"foreground_interval") {
            val cycle=kotlin.coroutines.coroutineContext[PilotCommitTrace.B6CycleContext]?.ticket
            val t=PilotCommitTrace.b6BeginApply(cycle,scope,listOf(remote), lease=cloudLease)!!
            PilotCommitTrace.b6ApplyEnd(t, lease=cloudLease)
            PilotCommitTrace.b6Rendered(7,"b6:${b.operationID}:$h",PilotCommitTrace.now())
        }
        assertTrue(PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.none { it.stage=="peer_expected_product_draw" })
    }
    @Test fun twoCyclesCannotJoinOldCommitOrWrongBodyNodeToExpectedNewBody()=runBlocking {
        val b=binding(peer=true); arm(b); var first:PilotCommitTrace.B6Ticket?=null
        observeCycle({ scope },"foreground_interval") {
            val cycle=kotlin.coroutines.coroutineContext[PilotCommitTrace.B6CycleContext]?.ticket
            first=PilotCommitTrace.b6BeginApply(cycle,scope,listOf(remote), lease=cloudLease)!!
            PilotCommitTrace.b6AppliedProductFacts(cycle,remote,7,0,"b".repeat(64),"2026-10-23T12:00:00Z")
            PilotCommitTrace.b6ApplyEnd(first, lease=cloudLease)
            PilotCommitTrace.b6Rendered(7,"b6:${b.operationID}:${"b".repeat(64)}",PilotCommitTrace.now())
        }
        assertTrue(PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.none { it.stage=="peer_expected_product_draw" })
        var second:PilotCommitTrace.B6Ticket?=null
        observeCycle({ scope },"foreground_interval") {
            val cycle=kotlin.coroutines.coroutineContext[PilotCommitTrace.B6CycleContext]?.ticket
            second=PilotCommitTrace.b6BeginApply(cycle,scope,listOf(remote), lease=cloudLease)!!
            PilotCommitTrace.b6AppliedProductFacts(cycle,remote,7,0,h,"2026-10-23T12:34:56.123456Z")
            PilotCommitTrace.b6ApplyEnd(second, lease=cloudLease)
            PilotCommitTrace.b6Rendered(7,"b6:${b.operationID}:${"b".repeat(64)}",PilotCommitTrace.now())
            assertTrue(PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.none { it.stage=="peer_expected_product_draw" })
            PilotCommitTrace.b6Rendered(7,"b6:${b.operationID}:$h",PilotCommitTrace.now())
        }
        val r=PilotCommitTrace.b6SnapshotFromTestOnly()!!
        val draw=r.events.single { it.stage=="peer_expected_product_draw" }
        val commit=r.events.single { it.stage=="peer_commit_return" && it.applyID==second!!.applyID }
        assertEquals(commit.cycleID,draw.cycleID); assertEquals(commit.entities,draw.entities)
        assertEquals(commit.remoteVersionHash,draw.remoteVersionHash); assertEquals(h,draw.bodyHash)
        assertNotEquals(first!!.cycleID,draw.cycleID)
    }

    @Test fun validDrawThenNewTargetApplyInvalidatesTerminalWithoutDiscardingRawEvidence()=runBlocking {
        val b=binding(peer=true); arm(b)
        observeCycle({ scope },"foreground_interval") {
            val cycle=kotlin.coroutines.coroutineContext[PilotCommitTrace.B6CycleContext]?.ticket
            val t=PilotCommitTrace.b6BeginApply(cycle,scope,listOf(remote), lease=cloudLease)!!
            PilotCommitTrace.b6AppliedProductFacts(cycle,remote,7,0,h,"2026-10-23T12:34:56Z")
            PilotCommitTrace.b6ApplyEnd(t, lease=cloudLease)
            PilotCommitTrace.b6Rendered(7,"b6:${b.operationID}:$h",PilotCommitTrace.now())
        }
        assertEquals(1,PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.count { it.stage=="peer_expected_product_draw" })
        observeCycle({ scope },"foreground_interval") {
            val cycle=kotlin.coroutines.coroutineContext[PilotCommitTrace.B6CycleContext]?.ticket
            assertNotNull(PilotCommitTrace.b6BeginApply(cycle,scope,listOf(remote), lease=cloudLease))
            val r=PilotCommitTrace.b6SnapshotFromTestOnly()!!
            assertEquals("TARGET_CHANGED_AFTER_DRAW",r.invalidReason)
            assertEquals(1,r.events.count { it.stage=="peer_expected_product_draw" })
            assertEquals(2,r.events.count { it.stage=="peer_transaction_begin" })
        }
    }

    @Test fun compositionBeforeCommitReturnCanQualifyOnlyAtLaterDrawWithoutRecomposition()=runBlocking {
        val product=Product(id=7,barcode="composition-fixture",productName="Expected")
        val body=PilotCommitTrace.hash(kotlinx.serialization.json.Json.encodeToString(
            CatalogProductSnapshot.serializer(),CatalogProductSnapshot.from(product)))
        val b=binding(peer=true).copy(expectedLocalBodyHash=body); arm(b)
        observeCycle({ scope },"foreground_interval") {
            val cycle=kotlin.coroutines.coroutineContext[PilotCommitTrace.B6CycleContext]?.ticket
            val t=PilotCommitTrace.b6BeginApply(cycle,scope,listOf(remote), lease=cloudLease)!!
            PilotCommitTrace.b6AppliedProductFacts(cycle,remote,7,0,body,"2026-10-23T12:34:56Z")
            val composedDetail=PilotCommitTrace.b6RenderDetail(product,"original")
            assertEquals("b6:${b.operationID}:$body",composedDetail)
            PilotCommitTrace.b6Rendered(7,composedDetail,PilotCommitTrace.now())
            assertTrue(PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.none { it.stage=="peer_expected_product_draw" })
            PilotCommitTrace.b6ApplyEnd(t, lease=cloudLease)
            // Same captured node detail; no observer-triggered recomposition or state invalidation.
            PilotCommitTrace.b6Rendered(7,composedDetail,PilotCommitTrace.now())
            val draw=PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.single { it.stage=="peer_expected_product_draw" }
            assertEquals(t.applyID,draw.applyID); assertEquals(t.cycleID,draw.cycleID)
        }
    }

    private val owner="00000000-0000-4000-8000-000000000100"
    private val shop="00000000-0000-4000-8000-000000000200"
    private val scope=PilotCommitTrace.hash(task126OwnerHash(owner)+"\nshop:"+shop)
    private val json=Json { encodeDefaults=true }
    private val gateScope by lazy { Task126OwnerStoreScope(task126OwnerHash(owner),"shop:"+shop,null) }
    private val gateState by lazy { Task126BusinessDataScopeState.ready(gateScope) }
    private val gate by lazy { Task126BusinessDataScopeFlightGate(gateState) }
    private fun authority(): PilotCommitTrace.B6Authority {
        val local=requireNotNull(gate.captureLocalPresentationStamp(gateState))
        val cloud=gate.captureBusinessDataScopeSignal(owner,shop)
        return PilotCommitTrace.B6Authority(PilotCommitTrace.B6AuthorityStamp(
            PilotCommitTrace.b6ScopeStamp(requireNotNull(cloud.lease.boundScope)),local.localGeneration,cloud.lease.generation)) {
            gate.isLocalPresentationStampCurrent(local,gateState) && gate.isCurrentBusinessDataScopeSignal(cloud)
        }
    }
    private val localLease: PilotCommitTrace.B6LeaseStamp? get() = runBlocking {
        gate.withLocalBusinessDataScopeFlight {
            PilotCommitTrace.b6LeaseStamp(kotlin.coroutines.coroutineContext[Task126BusinessDataScopeLeaseContext]?.lease)
        }
    }
    private val cloudLease: PilotCommitTrace.B6LeaseStamp? get() =
        PilotCommitTrace.b6LeaseStamp(gate.captureBusinessDataScopeSignal(owner,shop).lease)
    private suspend fun observeCycle(scope: () -> String, reason: String, body: suspend () -> Unit) =
        gate.withCurrentBusinessDataScopeFlight { PilotCommitTrace.b6ObserveCycle(scope,reason,body) }
    private fun productBody(p:Product)=PilotCommitTrace.hash(json.encodeToString(CatalogProductSnapshot.serializer(),CatalogProductSnapshot.from(p)))
    private fun sourceBinding(kind:String,body:String,localID:Long?=7L,entity:String?=remote):PilotCommitTrace.B6Binding {
        val n=mapOf("create" to 8,"update" to 4,"delete" to 5,"price" to 6,"history" to 7).getValue(kind)
        return binding().copy(sampleID="T20-G02-"+n.toString().padStart(2,'0'),category=kind,
            scopeHash=scope,localProductID=localID,entityHashes=entity?.let { listOf(PilotCommitTrace.hash(it)) }.orEmpty(),
            expectedLocalBodyHash=body,localEntityID=if(kind=="history") 9L else null,
            parentRemoteID=if(kind=="price") remote else null,
            expectedPriceValueJSON=if(kind=="price") buildJsonObject {put("productID",remote);put("type","retail");put("price","9");put("source","MANUAL");put("note",JsonNull)}.toString() else null)
    }
    private fun armSource(b:PilotCommitTrace.B6Binding) = requireNotNull(PilotCommitTrace.b6ArmFromTestOnly(b,scope,h,h,h,authority()))
    private fun entry(attempt:BusinessWriteAttempt):SyncEventOutboxEntry {
        val raw=json.encodeToString(BusinessWriteAttempt.serializer(),attempt)
        return SyncEventOutboxEntry(ownerUserId=owner,storeScope="shop:"+shop,domain=attempt.payload.kind,
            eventType=BUSINESS_WRITE_EVENT,source="local_business_write",sourceDeviceId=attempt.device,
            batchId=null,clientEventId="business-write-v1:"+PilotCommitTrace.hash(raw),
            changedCount=attempt.payload.revisions.size,entityIdsJson="{}",metadataJson=raw,createdAtMs=1)
    }
    private fun productFixture():Triple<Product,ProductRemoteRef,InventoryProductRow> {
        val p=Product(7,"B6-unique",productName="nonce expected",retailPrice=5.0)
        val ref=ProductRemoteRef(productId=7,remoteId=remote,localChangeRevision=4,lastSyncedLocalRevision=3,
            remoteUpdatedAt="2026-10-23T00:00:00.123456Z",localChangedFields="productname")
        val row=InventoryProductRow(remote,owner,shop,p.barcode,productName=p.productName,
            retailPrice=p.retailPrice,stockQuantity=p.stockQuantity,updatedAt=ref.remoteUpdatedAt)
        return Triple(p,ref,row)
    }
    @Test fun exactProductIntentSealDispatchAndRealAckPreserveUnknownVersion() {
        val (p,ref,row)=productFixture();armSource(sourceBinding("update",productBody(p)))
        val ticket=PilotCommitTrace.b6BeginLocal(7,scope, lease=localLease)
        PilotCommitTrace.b6CaptureProductRef(ref);PilotCommitTrace.b6LocalIntent(7,remote,4)
        PilotCommitTrace.b6CaptureProduct(p);PilotCommitTrace.b6LocalEnd(ticket,true,productBody(p), lease=localLease)
        PilotCommitTrace.b6PreparedProduct(p,ref,row, lease=cloudLease)
        val attempt=BusinessWriteAttempt(owner=owner,store="shop:"+shop,shop=shop,device="test-device",generation=null,localStore=gateScope.localStoreId,
            payload=BusinessWritePayload("PATCH",patchId=remote,patch=InventoryProductPatch(setOf("productname"),productName=p.productName),
                revisions=listOf(BusinessWriteRevision("PRODUCT",remote,4,fingerprintProductInbound(row),ref.remoteUpdatedAt))))
        val e=entry(attempt);PilotCommitTrace.b6Sealed(e,attempt, lease=cloudLease)
        PilotCommitTrace.b6Dispatched(e.copy(attemptCount=1,lastAttemptAtMs=2), lease=cloudLease)
        PilotCommitTrace.b6OwnAck(e,BusinessWriteConfirmation.LegacyAccepted,PilotCommitTrace.now(), lease=cloudLease)
        val r=PilotCommitTrace.b6SnapshotFromTestOnly()!!;assertNull(r.invalidReason)
        val ack=r.events.single { it.stage=="source_actual_ack_identity_persisted" }
        assertEquals(e.metadataJson,ack.sealedAttemptJSON);assertEquals(e.clientEventId,ack.operationKey)
        assertEquals(remote,ack.remoteID);assertNull(ack.versionRaw);assertEquals("VERSION_UNKNOWN",ack.outcome)
        assertTrue(requireNotNull(ack.originalIntentJSON).contains(ref.remoteUpdatedAt!!))
        assertEquals(PilotB6Values.product(row),ack.bodyValueJSON);assertFalse(r.nativeAcceptance)
        val prepared=json.encodeToString(CatalogProductSnapshot.serializer(),CatalogProductSnapshot.from(p))
        assertEquals(prepared,ack.preparedLocalBodyJSON)
        assertEquals(prepared,r.events.single { it.stage=="source_intent_sealed" }.preparedLocalBodyJSON)
        assertNull(r.events.single { it.stage=="source_exact_attempt_dispatched" }.preparedLocalBodyJSON)
    }
    @Test fun createdProductPreparedBodyKeepsActualLocalIdentityThroughSealAndAck() {
        val (p,ref,row)=productFixture()
        armSource(sourceBinding("create",PilotCommitTrace.b6CreateProductBodyHash(p),null,null))
        val ticket=PilotCommitTrace.b6BeginKind("create",null,scope,lease=localLease)
        PilotCommitTrace.b6CaptureProductRef(ref);PilotCommitTrace.b6LocalIntent(p.id,remote,4)
        PilotCommitTrace.b6CaptureProduct(p);PilotCommitTrace.b6KindEnd(ticket,PilotCommitTrace.now(),true,lease=localLease)
        PilotCommitTrace.b6PreparedProduct(p,ref,row,lease=cloudLease)
        val attempt=BusinessWriteAttempt(owner=owner,store="shop:"+shop,shop=shop,device="test-device",generation=null,localStore=gateScope.localStoreId,
            payload=BusinessWritePayload("PRODUCTS",products=listOf(row),revisions=listOf(BusinessWriteRevision("PRODUCT",remote,4,fingerprintProductInbound(row)))))
        val e=entry(attempt);PilotCommitTrace.b6Sealed(e,attempt,lease=cloudLease);PilotCommitTrace.b6Dispatched(e,lease=cloudLease)
        PilotCommitTrace.b6OwnAck(e,BusinessWriteConfirmation.LegacyAccepted,PilotCommitTrace.now(),lease=cloudLease)
        val receipt=PilotCommitTrace.b6SnapshotFromTestOnly()!!;assertNull(receipt.invalidReason)
        val raw=json.encodeToString(CatalogProductSnapshot.serializer(),CatalogProductSnapshot.from(p))
        assertEquals(raw,receipt.events.single { it.stage=="source_intent_sealed" }.preparedLocalBodyJSON)
        assertEquals(raw,receipt.events.single { it.stage=="source_actual_ack_identity_persisted" }.preparedLocalBodyJSON)
    }
    @Test fun deleteUsesBaselineAndTombstoneTokenWithoutPreparedLocalBody() {
        val (p,_,_)=productFixture()
        armSource(sourceBinding("delete",PilotCommitTrace.hash("{\"deleted\":true}")))
        val ticket=PilotCommitTrace.b6BeginKind("delete",p.id,scope,lease=localLease)
        val tombstone=PendingCatalogTombstone(id=91,entityType=PendingCatalogTombstoneEntityTypes.PRODUCT,remoteId=remote,enqueuedAtMs=2)
        PilotCommitTrace.b6CaptureDelete(p,tombstone);PilotCommitTrace.b6KindEnd(ticket,PilotCommitTrace.now(),true,lease=localLease)
        val patch=CatalogTombstonePatch(remote,owner,shop,"2026-10-24T00:00:00.123456Z","2026-10-24T00:00:00.123456Z")
        PilotCommitTrace.b6DeleteDispatched(tombstone.copy(attemptCount=1),patch,lease=cloudLease)
        PilotCommitTrace.b6DeleteAcknowledged(tombstone,PilotCommitTrace.now(),lease=cloudLease)
        val receipt=PilotCommitTrace.b6SnapshotFromTestOnly()!!;assertNull(receipt.invalidReason)
        val ack=receipt.events.single { it.stage=="source_actual_ack_identity_persisted" }
        val token=Json.parseToJsonElement(requireNotNull(ack.originalIntentJSON)).jsonObject
        assertEquals(Json.parseToJsonElement(json.encodeToString(CatalogProductSnapshot.serializer(),CatalogProductSnapshot.from(p))),token["baseline"])
        assertNotNull(token["tombstone"])
        assertTrue(receipt.events.all { it.preparedLocalBodyJSON==null })
    }

    @Test fun changedOriginalBodyBeforePreparationCannotProduceOwnAck() {
        val (p,ref,row)=productFixture();armSource(sourceBinding("update",productBody(p)))
        val ticket=PilotCommitTrace.b6BeginLocal(7,scope, lease=localLease)
        PilotCommitTrace.b6CaptureProductRef(ref);PilotCommitTrace.b6LocalIntent(7,remote,4)
        PilotCommitTrace.b6CaptureProduct(p);PilotCommitTrace.b6LocalEnd(ticket,true,productBody(p), lease=localLease)
        PilotCommitTrace.b6PreparedProduct(p.copy(productName="different"),ref,row, lease=cloudLease)
        assertEquals("SOURCE_INTENT_CHANGED_BEFORE_SEAL",PilotCommitTrace.b6SnapshotFromTestOnly()?.invalidReason)
        val attempt=BusinessWriteAttempt(owner=owner,store="shop:"+shop,shop=shop,device="test-device",generation=null,localStore=gateScope.localStoreId,
            payload=BusinessWritePayload("PRODUCTS",products=listOf(row),revisions=listOf(BusinessWriteRevision("PRODUCT",remote,4,fingerprintProductInbound(row)))))
        PilotCommitTrace.b6Sealed(entry(attempt),attempt,lease=cloudLease)
        assertTrue(PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.none {
            it.stage=="source_intent_sealed" || it.stage=="source_actual_ack_identity_persisted" || it.preparedLocalBodyJSON!=null })
    }
    @Test fun localCommitAloneIsNeverAnOwnAckObservation() {
        val (p,ref,_)=productFixture();armSource(sourceBinding("create",PilotCommitTrace.b6CreateProductBodyHash(p),null,null))
        val ticket=PilotCommitTrace.b6BeginKind("create",null,scope, lease=localLease)
        PilotCommitTrace.b6CaptureProductRef(ref);PilotCommitTrace.b6LocalIntent(p.id,remote,4)
        PilotCommitTrace.b6CaptureProduct(p);PilotCommitTrace.b6KindEnd(ticket,PilotCommitTrace.now(),true, lease=localLease)
        assertTrue(PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.any { it.stage=="local_commit_return" })
        assertTrue(PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.none { it.stage=="source_actual_ack_identity_persisted" })
    }
    @Test fun exactPriceIntentBindsGeneratedChildIdAndActualParentWithoutParentDirtyShortcut() {
        val price=ProductPrice(21,7,"RETAIL",9.0,"2026-10-23 12:34:56","MANUAL")
        val body=PilotCommitTrace.hash(PilotB6Values.localPrice(price));armSource(sourceBinding("price",body,entity=null))
        val ticket=PilotCommitTrace.b6BeginKind("price",7,scope, lease=localLease)
        PilotCommitTrace.b6CapturePrice(price);PilotCommitTrace.b6KindEnd(ticket,PilotCommitTrace.now(),true, lease=localLease)
        val id="00000000-0000-4000-8000-000000000300"
        val local=ProductPricePushRow(price.id,price.productId,price.type,price.price,price.effectiveAt,price.source,price.note,price.createdAt,remote,null)
        val row=InventoryProductPriceRow(id,owner,shop,remote,price.type,price.price,effectiveAt=price.effectiveAt,source=price.source,createdAt=price.createdAt)
        PilotCommitTrace.b6PreparedPrice(local,row, lease=cloudLease)
        val attempt=BusinessWriteAttempt(owner=owner,store="shop:"+shop,shop=shop,device="test-device",generation=null,localStore=gateScope.localStoreId,
            payload=BusinessWritePayload("PRICES",prices=listOf(row),revisions=listOf(BusinessWriteRevision("PRICE",id,0,null,localPriceId=price.id))))
        val e=entry(attempt);PilotCommitTrace.b6Sealed(e,attempt, lease=cloudLease);PilotCommitTrace.b6Dispatched(e, lease=cloudLease)
        PilotCommitTrace.b6OwnAck(e,BusinessWriteConfirmation.LegacyAccepted,PilotCommitTrace.now(), lease=cloudLease)
        val ack=PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.single { it.stage=="source_actual_ack_identity_persisted" }
        assertEquals(id,ack.remoteID);assertEquals(price.id,ack.localID);assertEquals(PilotB6Values.price(row),ack.bodyValueJSON)
        assertEquals(PilotB6Values.localPrice(price),ack.preparedLocalBodyJSON)
        assertEquals(ack.preparedLocalBodyJSON,PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.single { it.stage=="source_intent_sealed" }.preparedLocalBodyJSON)
        assertNull(PilotCommitTrace.b6SnapshotFromTestOnly()?.invalidReason)
    }
    @Test fun pricePreparationCannotSubstituteParentOfIdenticalPriceBody() {
        val price=ProductPrice(21,7,"RETAIL",9.0,"2026-10-23 12:34:56","MANUAL")
        armSource(sourceBinding("price",PilotCommitTrace.hash(PilotB6Values.localPrice(price)),entity=null))
        val ticket=PilotCommitTrace.b6BeginKind("price",7,scope, lease=localLease);PilotCommitTrace.b6CapturePrice(price)
        PilotCommitTrace.b6KindEnd(ticket,PilotCommitTrace.now(),true, lease=localLease)
        val other=UUID.randomUUID().toString()
        PilotCommitTrace.b6PreparedPrice(ProductPricePushRow(21,8,"RETAIL",9.0,price.effectiveAt,"MANUAL",null,price.createdAt,other,null),
            InventoryProductPriceRow(UUID.randomUUID().toString(),owner,shop,other,"RETAIL",9.0,effectiveAt=price.effectiveAt,createdAt=price.createdAt), lease=cloudLease)
        assertEquals("PRICE_PARENT_IDENTITY_MISMATCH",PilotCommitTrace.b6SnapshotFromTestOnly()?.invalidReason)
    }
    @Test fun deleteDatasetRejectsLazyAbsencePendingLoadPlaceholdersAndInvisibleControl() {
        val b=binding(peer=true).copy(sampleID="T20-G01-06",category="delete",expectedLocalBodyHash=PilotCommitTrace.hash("{\"deleted\":true}"),
            expectedVisibleProjectionHash=h,deleteQuery="B6-",deletedBarcode="B6-target",controlBarcode="B6-control")
        arm(b);val control=Product(8,"B6-control",productName="control")
        assertNull(PilotCommitTrace.b6DeleteRenderDetail("B6-","B6-",listOf(control),false,0,setOf(8)))
        assertNull(PilotCommitTrace.b6DeleteRenderDetail("B6-","B6-",listOf(control),true,1,setOf(8)))
        assertNull(PilotCommitTrace.b6DeleteRenderDetail("B6-","B6-",listOf(control),true,0,emptySet()))
        assertNull(PilotCommitTrace.b6DeleteRenderDetail("other","other",listOf(control),true,0,setOf(8)))
        assertNull(PilotCommitTrace.b6DeleteRenderDetail("B6-","B6-",listOf(control,Product(7,"B6-target",productName="target")),true,0,setOf(8)))
        assertNotNull(PilotCommitTrace.b6DeleteRenderDetail("B6-","B6-",listOf(control),true,0,setOf(8)))
    }

    @Test fun historyPreparationPreservesWholeEditableAndCompleteBeforeGeneratedRemoteId() {
        val history=HistoryEntry(uid=9,id="fixture",displayName="unique history nonce",timestamp="2026-10-23 12:00:00",
            data=listOf(listOf("barcode","quantity"),listOf("B6", "2")),
            editable=listOf(listOf("B6","2")),complete=listOf(false))
        val b=sourceBinding("history",PilotCommitTrace.hash(PilotB6Values.localHistory(history)),null,null)
        armSource(b);val ticket=PilotCommitTrace.b6BeginKind("history",9,scope, lease=localLease)
        PilotCommitTrace.b6CaptureHistory(history,null);PilotCommitTrace.b6KindEnd(ticket,PilotCommitTrace.now(),true, lease=localLease)
        val rid="00000000-0000-4000-8000-000000000900"
        val ref=HistoryEntryRemoteRef(historyEntryUid=9,remoteId=rid)
        val payload=SessionRemotePayload(rid,2,history.timestamp,history.supplier,history.category,history.isManualEntry,
            history.data,history.displayName,SessionOverlay(editable=history.editable,complete=history.complete))
        val row=payload.toSharedSheetSessionUpsertRow(owner,shop)
        PilotCommitTrace.b6PreparedHistory(history,ref,payload,owner,shop, lease=cloudLease)
        val attempt=BusinessWriteAttempt(owner=owner,store="shop:"+shop,shop=shop,device="test-device",generation=null,localStore=gateScope.localStoreId,
            payload=BusinessWritePayload("HISTORY",history=listOf(row),revisions=listOf(BusinessWriteRevision("HISTORY",rid,0,payload.payloadFingerprint()))))
        val e=entry(attempt);PilotCommitTrace.b6Sealed(e,attempt, lease=cloudLease);PilotCommitTrace.b6Dispatched(e, lease=cloudLease)
        PilotCommitTrace.b6OwnAck(e,BusinessWriteConfirmation.LegacyAccepted,PilotCommitTrace.now(), lease=cloudLease)
        val r=PilotCommitTrace.b6SnapshotFromTestOnly()!!;assertNull(r.invalidReason)
        val ack=r.events.single { it.stage=="source_actual_ack_identity_persisted" }
        assertEquals(rid,ack.remoteID);assertEquals(PilotB6Values.history(row),ack.bodyValueJSON)
        assertTrue(requireNotNull(ack.originalIntentJSON).contains("complete"))
        assertEquals(PilotB6Values.localHistory(history),ack.preparedLocalBodyJSON)
        assertEquals(ack.preparedLocalBodyJSON,r.events.single { it.stage=="source_intent_sealed" }.preparedLocalBodyJSON)
    }
    @Test fun typedHistoryProjectionBeforeCommitCannotQualifyAndLaterDrawUsesSameApply()=runBlocking {
        val local=HistoryEntry(uid=9,id="fixture",displayName="Expected visible nonce",timestamp="2026-10-23 12:00:00",
            data=listOf(listOf("B6")),editable=listOf(listOf("B6")),complete=listOf(false))
        val projection=buildJsonObject {put("route","History");put("displayTitle",local.displayName);put("metadataText","metadata")
            put("displayName",local.displayName);put("timestamp",local.timestamp);put("supplier",local.supplier);put("category",local.category)}.toString()
        val b=binding(peer=true).copy(sampleID="T20-G01-08",category="history",localProductID=null,localEntityID=9,
            expectedLocalBodyHash=PilotCommitTrace.hash(PilotB6Values.localHistory(local)),expectedVisibleProjectionHash=PilotCommitTrace.hash(projection))
        arm(b)
        val row=SharedSheetSessionRecord(remote,2,local.displayName,local.timestamp,local.supplier,local.category,false,local.data,
            SessionOverlay(editable=local.editable,complete=local.complete),updatedAt="2026-10-23T12:00:00.123456Z")
        observeCycle({scope},"foreground_interval") {
            val cycle=kotlin.coroutines.coroutineContext[PilotCommitTrace.B6CycleContext]?.ticket
            val ticket=PilotCommitTrace.b6BeginApply(cycle,scope,listOf(remote), lease=cloudLease)!!
            PilotCommitTrace.b6RememberHistoryRows(listOf(row));PilotCommitTrace.b6AppliedHistory(cycle,remote,local,0)
            val display=HistoryDisplayEntry(HistoryEntryListItem(9,"fixture",local.displayName,local.timestamp))
            val detail=PilotCommitTrace.b6HistoryRenderDetail(display,local.displayName,"metadata")
            PilotCommitTrace.b6ProjectionRendered("history",9,detail,PilotCommitTrace.now())
            assertTrue(PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.none { it.stage=="peer_expected_entity_draw" })
            PilotCommitTrace.b6ApplyEnd(ticket, lease=cloudLease)
            PilotCommitTrace.b6ProjectionRendered("history",9,detail,PilotCommitTrace.now())
            val draw=PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.single { it.stage=="peer_expected_entity_draw" }
            assertEquals(ticket.applyID,draw.applyID);assertEquals(ticket.cycleID,draw.cycleID)
            assertEquals(row.updatedAt,draw.versionRaw);assertEquals(PilotB6Values.history(row),draw.bodyValueJSON)
        }
    }
    @Test fun finalClockReplyKeepsActualProcessBootAndRefusesReusedNonceOrDifferentBoot() {
        val b=binding().copy(deviceHash="d".repeat(64),artifactHash="b".repeat(64),
            preflightHash="c".repeat(64),manifestHash="e".repeat(64))
        val initial=requireNotNull(PilotCommitTrace.b6ArmFromTestOnly(b,scope,b.deviceHash,b.artifactHash,h,authority()))
        val nonce=UUID.randomUUID().toString()
        assertNull(PilotCommitTrace.b6CalibrationFromTestOnly(b.nonce,h))
        assertNull(PilotCommitTrace.b6CalibrationFromTestOnly(nonce,"b".repeat(64)))
        val final=PilotCommitTrace.b6CalibrationFromTestOnly(nonce,h)!!
        assertEquals(initial.clockID,final.clockID);assertEquals(initial.participantPID,final.participantPID)
        assertEquals(initial.bootHash,final.bootHash);assertTrue(final.remoteNS>=initial.remoteNS)
        assertEquals(b.operationID,initial.operationID);assertEquals(b.operationID,final.operationID)
        assertEquals(b.deviceHash,initial.deviceHash);assertEquals(initial.deviceHash,final.deviceHash)
        assertEquals(b.artifactHash,final.artifactHash)
        assertEquals(b.preflightHash,final.preflightHash);assertEquals(b.manifestHash,final.manifestHash)
    }

    @Test fun generatedPriceTimestampMustRemainExactFromLocalIntentToSeal() {
        val price=ProductPrice(21,7,"RETAIL",9.0,"2026-10-23 12:34:56","MANUAL")
        armSource(sourceBinding("price",PilotCommitTrace.hash(PilotB6Values.localPrice(price)),entity=null).copy(expectedLocalBodyHash=null))
        val ticket=PilotCommitTrace.b6BeginKind("price",7,scope, lease=localLease);PilotCommitTrace.b6CapturePrice(price)
        PilotCommitTrace.b6KindEnd(ticket,PilotCommitTrace.now(),true, lease=localLease)
        assertNull(PilotCommitTrace.b6SnapshotFromTestOnly()?.invalidReason)
        val changed=price.copy(effectiveAt="2026-10-23 12:34:57")
        val local=ProductPricePushRow(changed.id,changed.productId,changed.type,changed.price,changed.effectiveAt,changed.source,changed.note,changed.createdAt,remote,null)
        val row=InventoryProductPriceRow(UUID.randomUUID().toString(),owner,shop,remote,changed.type,changed.price,
            effectiveAt=changed.effectiveAt,source=changed.source,createdAt=changed.createdAt)
        PilotCommitTrace.b6PreparedPrice(local,row, lease=cloudLease)
        assertEquals("SOURCE_INTENT_CHANGED_BEFORE_SEAL",PilotCommitTrace.b6SnapshotFromTestOnly()?.invalidReason)
    }
    @Test fun priceDrawMatchesActualGeneratedFullBodyAndCannotReuseOtherDateOrPrecommitDraw()=runBlocking {
        val local=ProductPrice(21,7,"RETAIL",9.0,"2026-10-23 12:34:56","MANUAL")
        val requested=buildJsonObject {put("productID",remote);put("type","retail");put("price","9");put("source","MANUAL");put("note",JsonNull)}.toString()
        val visible=buildJsonObject {put("price","9");put("type","RETAIL");put("source","Manual")}.toString()
        val b=binding(peer=true).copy(sampleID="T20-G01-07",category="price",entityHashes=emptyList(),expectedLocalBodyHash=null,
            parentRemoteID=remote,expectedPriceValueJSON=requested,expectedPriceVisibleJSON=visible)
        arm(b)
        val rid="00000000-0000-4000-8000-000000000300"
        val row=InventoryProductPriceRow(rid,owner,shop,remote,"RETAIL",9.0,effectiveAt=local.effectiveAt,source="MANUAL",
            createdAt=local.createdAt,updatedAt="2026-10-23T12:34:56.123456Z")
        observeCycle({scope},"foreground_interval") {
            val cycle=kotlin.coroutines.coroutineContext[PilotCommitTrace.B6CycleContext]?.ticket
            val ticket=PilotCommitTrace.b6BeginApply(cycle,scope,listOf(rid), lease=cloudLease)!!
            PilotCommitTrace.b6AppliedPrice(cycle,row,local)
            val detail=PilotCommitTrace.b6PriceHistoryRenderDetail(local,"9","actual visible date","Manual")
            PilotCommitTrace.b6ProjectionRendered("price",21,detail,PilotCommitTrace.now())
            assertTrue(PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.none { it.stage=="peer_expected_entity_draw" })
            PilotCommitTrace.b6ApplyEnd(ticket, lease=cloudLease)
            val stale=PilotCommitTrace.b6PriceHistoryRenderDetail(local.copy(effectiveAt="2026-10-23 12:34:55"),"9","old date","Manual")
            PilotCommitTrace.b6ProjectionRendered("price",21,stale,PilotCommitTrace.now())
            assertTrue(PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.none { it.stage=="peer_expected_entity_draw" })
            PilotCommitTrace.b6ProjectionRendered("price",21,detail,PilotCommitTrace.now())
            val draw=PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.single { it.stage=="peer_expected_entity_draw" }
            assertEquals(PilotB6Values.price(row),draw.bodyValueJSON);assertEquals(row.updatedAt,draw.versionRaw)
            assertTrue(requireNotNull(draw.visibleValueJSON).contains("actual visible date"));assertEquals(ticket.applyID,draw.applyID)
        }
    }

    private fun committedProductIntent(prepare: Boolean = true): Pair<PilotCommitTrace.B6Ticket?,BusinessWriteAttempt> {
        val (p,ref,row)=productFixture();armSource(sourceBinding("update",productBody(p)))
        val ticket=PilotCommitTrace.b6BeginLocal(7,scope,lease=localLease)
        PilotCommitTrace.b6CaptureProductRef(ref);PilotCommitTrace.b6LocalIntent(7,remote,4)
        PilotCommitTrace.b6CaptureProduct(p);PilotCommitTrace.b6LocalEnd(ticket,true,productBody(p),lease=localLease)
        if(prepare) PilotCommitTrace.b6PreparedProduct(p,ref,row,lease=cloudLease)
        return ticket to BusinessWriteAttempt(owner=owner,store="shop:"+shop,shop=shop,device="test-device",generation="baseline-G1",
            payload=BusinessWritePayload("PATCH",patchId=remote,patch=InventoryProductPatch(setOf("productname"),productName=p.productName),
                revisions=listOf(BusinessWriteRevision("PRODUCT",remote,4,fingerprintProductInbound(row),ref.remoteUpdatedAt))))
    }
    @Test fun sameScopeGenerationReplacementAfterLocalCommitCannotSealIdenticalIntent()=runBlocking {
        val (_,attempt)=committedProductIntent(prepare=false)
        val original=PilotCommitTrace.b6SnapshotFromTestOnly()!!
        gate.withBusinessDataScopeTransition { Unit } // Same state object/body/owner/shop, epochs change.
        val (p,ref,row)=productFixture()
        PilotCommitTrace.b6PreparedProduct(p,ref,row,lease=cloudLease)
        PilotCommitTrace.b6Sealed(entry(attempt),attempt,lease=cloudLease)
        val after=PilotCommitTrace.b6SnapshotFromTestOnly()!!
        assertEquals("BUSINESS_GENERATION_CHANGED",after.invalidReason)
        assertEquals(original.events,after.events)
        assertTrue(after.events.none { it.stage=="source_intent_sealed" || it.stage=="source_actual_ack_identity_persisted" })
    }
    @Test fun sameScopeReplacementBetweenDispatchAndAckCannotCertifyOldAttempt()=runBlocking {
        val (_,attempt)=committedProductIntent();val e=entry(attempt)
        PilotCommitTrace.b6Sealed(e,attempt,lease=cloudLease);PilotCommitTrace.b6Dispatched(e,lease=cloudLease)
        val before=PilotCommitTrace.b6SnapshotFromTestOnly()!!
        assertTrue(before.events.any { it.stage=="source_exact_attempt_dispatched" })
        gate.withBusinessDataScopeTransition { Unit }
        PilotCommitTrace.b6OwnAck(e,BusinessWriteConfirmation.LegacyAccepted,PilotCommitTrace.now(),lease=cloudLease)
        val after=PilotCommitTrace.b6SnapshotFromTestOnly()!!
        assertEquals("BUSINESS_GENERATION_CHANGED",after.invalidReason)
        assertTrue(after.events.none { it.stage=="source_actual_ack_identity_persisted" })
        assertEquals(before.events,after.events)
    }
    @Test fun peerCommitCannotJoinSameBodyDrawAfterSameScopeGenerationReplacement()=runBlocking {
        val b=binding(peer=true);arm(b)
        observeCycle({scope},"foreground_interval") {
            val cycle=kotlin.coroutines.coroutineContext[PilotCommitTrace.B6CycleContext]?.ticket
            val apply=PilotCommitTrace.b6BeginApply(cycle,scope,listOf(remote),lease=cloudLease)!!
            PilotCommitTrace.b6AppliedProductFacts(cycle,remote,7,0,h,"2026-10-23T12:34:56.123456Z")
            PilotCommitTrace.b6ApplyEnd(apply,lease=cloudLease)
        }
        val before=PilotCommitTrace.b6SnapshotFromTestOnly()!!
        assertTrue(before.events.any { it.stage=="peer_commit_return" })
        gate.withBusinessDataScopeTransition { Unit }
        PilotCommitTrace.b6Rendered(7,"b6:${b.operationID}:$h",PilotCommitTrace.now())
        val after=PilotCommitTrace.b6SnapshotFromTestOnly()!!
        assertEquals("BUSINESS_GENERATION_CHANGED",after.invalidReason)
        assertTrue(after.events.none { it.stage=="peer_expected_product_draw" })
        assertEquals(before.events,after.events)
    }
    @Test fun actualBoundaryLeaseMustMatchCapturedEpochEvenWhenAuthorityCallbackStillCurrent() {
        arm(binding());val wrong=requireNotNull(localLease).copy(generation=requireNotNull(localLease).generation+1)
        assertNull(PilotCommitTrace.b6BeginLocal(7,scope,lease=wrong))
        assertEquals("BUSINESS_LEASE_CHANGED",PilotCommitTrace.b6SnapshotFromTestOnly()?.invalidReason)
        assertTrue(PilotCommitTrace.b6SnapshotFromTestOnly()!!.events.isEmpty())
    }
    @Test fun missingOrForeignScopeLeaseCannotStartSample() {
        arm(binding())
        assertNull(PilotCommitTrace.b6BeginLocal(7,scope,lease=null))
        assertEquals("BUSINESS_LEASE_CHANGED",PilotCommitTrace.b6SnapshotFromTestOnly()?.invalidReason)
        disarm();arm(binding())
        val lease=requireNotNull(localLease)
        assertNull(PilotCommitTrace.b6BeginLocal(7,scope,lease=lease.copy(scope=lease.scope.copy(localStoreID="other"))))
        assertEquals("BUSINESS_LEASE_CHANGED",PilotCommitTrace.b6SnapshotFromTestOnly()?.invalidReason)
    }
    @Test fun localAndCloudEpochsRemainDistinctAndExactAcrossSourceLineage() {
        // Cloud availability changes while the same locally qualified state remains admitted.
        gate.updateState(gateState.copy(status=Task126BusinessDataScopeStatus.CHECKING,localAccessScope=gateScope))
        gate.updateState(gateState)
        val (_,attempt)=committedProductIntent();val e=entry(attempt)
        PilotCommitTrace.b6Sealed(e,attempt,lease=cloudLease);PilotCommitTrace.b6Dispatched(e,lease=cloudLease)
        PilotCommitTrace.b6OwnAck(e,BusinessWriteConfirmation.LegacyAccepted,PilotCommitTrace.now(),lease=cloudLease)
        val receipt=PilotCommitTrace.b6SnapshotFromTestOnly()!!
        assertNull(receipt.invalidReason)
        val stamp=requireNotNull(receipt.authorityStamp)
        assertNotEquals(stamp.localGeneration,stamp.cloudGeneration)
        val begin=receipt.events.single { it.stage=="local_transaction_begin" }
        val ack=receipt.events.single { it.stage=="source_actual_ack_identity_persisted" }
        assertEquals(stamp,begin.authorityStamp);assertEquals(stamp,ack.authorityStamp)
        assertEquals(true,begin.leaseStamp?.localOnly);assertEquals(stamp.localGeneration,begin.leaseStamp?.generation)
        assertEquals(false,ack.leaseStamp?.localOnly);assertEquals(stamp.cloudGeneration,ack.leaseStamp?.generation)
    }
    @Test fun authorityObserverFailureNeverThrowsOrChangesBusinessBody()=runBlocking {
        val b=binding(peer=true);val original=authority();var throwObservation=false
        val observed=PilotCommitTrace.B6Authority(original.stamp) { if(throwObservation) error("observer-only") else original.isCurrent() }
        assertNotNull(PilotCommitTrace.b6ArmFromTestOnly(b,scope,h,h,h,observed))
        throwObservation=true;var called=false
        observeCycle({scope},"foreground_interval") { called=true }
        assertTrue(called)
        assertEquals("BUSINESS_GENERATION_CHANGED",PilotCommitTrace.b6SnapshotFromTestOnly()?.invalidReason)
    }

}
