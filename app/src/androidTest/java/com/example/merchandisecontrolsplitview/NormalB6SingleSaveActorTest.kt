package com.example.merchandisecontrolsplitview

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.data.CatalogProductSnapshot
import com.example.merchandisecontrolsplitview.data.PilotCommitTrace
import com.example.merchandisecontrolsplitview.data.Product
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Protocol/latch negatives only. No UI action, app launch, auth, repository, sync or table access. */
@RunWith(AndroidJUnit4::class)
class NormalB6SingleSaveActorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun hash(value: String) = NormalB6SingleSaveActor.sha(value.toByteArray())
    private fun sorted(value: JsonElement): JsonElement = when(value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(value.map(::sorted))
        else -> value
    }
    private fun wire(value: JsonObject) = (sorted(value).toString()+"\n").toByteArray()
    private fun json(vararg values: Pair<String,JsonElement>) = JsonObject(linkedMapOf(*values))
    private fun JsonObject.change(key: String, value: JsonElement) = JsonObject(toMutableMap().apply { put(key,value) })
    private fun rejected(body: () -> Unit) {
        var rejected=false
        try { body() } catch (_: Exception) { rejected=true }
        assertTrue("Expected fail closed",rejected)
    }
    private inner class Fixture : AutoCloseable {
        val directory=File(instrumentation.targetContext.cacheDir.canonicalFile,"b6-save-codec-test-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val operation="11111111-2222-4333-8444-555555555555"
        val manifest="1".repeat(64)
        val body=JsonObject(Json.encodeToJsonElement(CatalogProductSnapshot.serializer(),
            CatalogProductSnapshot.from(Product(id=0,barcode="B6-SYNTHETIC",productName="Synthetic",retailPrice=1000.0)))
            .jsonObject.filterKeys { it!="id" }).toString()
        val binding=PilotCommitTrace.B6Binding(manifest,"5".repeat(64),"T20-G02-03",operation,
            "33333333-4444-4555-8666-777777777777","source","create","6".repeat(64),"4".repeat(64),
            "3".repeat(64),emptyList(),null,hash(body),expectedBarcode="B6-SYNTHETIC",
            timingMethod=PilotCommitTrace.B6_CAUSAL_METHOD,sourceAdmissionNonce="44444444-5555-4666-8777-888888888888")
        val reply=PilotCommitTrace.B6Reply(binding.nonce,"android-b6-synthetic",1,android.os.Process.myPid(),
            "2".repeat(64),binding.artifactHash,operation,binding.deviceHash,binding.preflightHash,manifest)
        val identity=json("clock_id" to JsonPrimitive(reply.clockID),"participant_pid" to JsonPrimitive(reply.participantPID),
            "participant_boot_sha256" to JsonPrimitive(reply.bootHash),"artifact_sha256" to JsonPrimitive(reply.artifactHash),
            "device_sha256" to JsonPrimitive(reply.deviceHash),"ordinary_preflight_sha256" to JsonPrimitive(reply.preflightHash))
        val witness=File(directory,"source-idle-witness.json").also { B6PublishedFile.publish(it,json("kind" to JsonPrimitive("B6_SOURCE_IDLE_WITNESS"),
            "operation_id" to JsonPrimitive(operation),"manifest_sha256" to JsonPrimitive(manifest),
            "nonce" to JsonPrimitive(binding.sourceAdmissionNonce),"identity" to identity).toString().toByteArray()) }
        val ownerIdentity=json("ownerID" to JsonPrimitive("22222222-3333-4444-8555-666666666666"),
            "ownerPID" to JsonPrimitive(901),"hostBootIdentityHash" to JsonPrimitive("6".repeat(64)),
            "clockStartedNS" to JsonPrimitive(1000000000L),"operationID" to JsonPrimitive(operation),
            "manifestSHA256" to JsonPrimitive(manifest),"timing_method" to JsonPrimitive(PilotCommitTrace.B6_CAUSAL_METHOD))
        val ready=JsonObject(ownerIdentity+mapOf("kind" to JsonPrimitive("B6_READY"),"bindingSHA256" to JsonPrimitive("7".repeat(64))))
        val accept=JsonObject(ownerIdentity+mapOf("kind" to JsonPrimitive("B6_SOURCE_IDLE_WITNESS_ACCEPTED"),
            "controlSequence" to JsonPrimitive(9),"monotonicNS" to JsonPrimitive(1100000000L),
            "sha256" to JsonPrimitive(NormalB6SingleSaveActor.sha(witness.readBytes()))))
        val readyRaw=String(wire(ready));val acceptRaw=String(wire(accept))
        val config=json("schema" to JsonPrimitive("b6-normal-single-save-config-v1"),
            "campaign" to JsonPrimitive("mobile-sync-execution-20261016-resume"),"sample_id" to JsonPrimitive(binding.sampleID),
            "operation_id" to JsonPrimitive(operation),"manifest_sha256" to JsonPrimitive(manifest),
            "owner_ready_sha256" to JsonPrimitive(hash(readyRaw)),"owner_binding_sha256" to JsonPrimitive("7".repeat(64)),
            "expected_accept_control_sequence" to JsonPrimitive(9),"expected_local_body_raw" to JsonPrimitive(body))
        val permit=json("schema" to JsonPrimitive(NormalB6SingleSaveActor.SCHEMA),
            "campaign" to config.getValue("campaign"),"sample_id" to JsonPrimitive(binding.sampleID),
            "operation_id" to JsonPrimitive(operation),"manifest_sha256" to JsonPrimitive(manifest),"source_identity" to identity,
            "owner_ready_raw" to JsonPrimitive(readyRaw),"owner_ready_sha256" to JsonPrimitive(hash(readyRaw)),
            "owner_accept_raw" to JsonPrimitive(acceptRaw),"owner_accept_sha256" to JsonPrimitive(hash(acceptRaw)),
            "source_idle_witness_sha256" to JsonPrimitive(NormalB6SingleSaveActor.sha(witness.readBytes())))
        fun actor(value:JsonObject=config)=NormalB6SingleSaveActor(instrumentation,binding,sorted(value).toString(),directory) { true }
        override fun close() { check(directory.deleteRecursively()) } // Only this test's fresh UUID directory.
    }

    @Test fun validPermitJoinsPinnedOwnerAndOwnWitness() = Fixture().use { f ->
        assertEquals(f.permit,f.actor().validatePermit(wire(f.permit),f.reply,f.witness))
    }
    @Test fun changedOperationManifestOrSourceIdentityIsRejected() = Fixture().use { f ->
        val identityChanges=listOf(
            "participant_pid" to JsonPrimitive(f.reply.participantPID+1), "clock_id" to JsonPrimitive("other"),
            "artifact_sha256" to JsonPrimitive("8".repeat(64)), "participant_boot_sha256" to JsonPrimitive("8".repeat(64)),
            "device_sha256" to JsonPrimitive("8".repeat(64)), "ordinary_preflight_sha256" to JsonPrimitive("8".repeat(64)))
        val changes=listOf(f.permit.change("operation_id",JsonPrimitive(UUID.randomUUID().toString())),
            f.permit.change("manifest_sha256",JsonPrimitive("8".repeat(64)))) +
            identityChanges.map { (key,value) -> f.permit.change("source_identity",f.identity.change(key,value)) }
        changes.forEach { changed -> rejected { f.actor().validatePermit(wire(changed),f.reply,f.witness) } }
    }
    @Test fun changedOwnerSequenceOrWitnessAcceptanceIsRejected() = Fixture().use { f ->
        listOf(f.accept.change("ownerPID",JsonPrimitive(902)),f.accept.change("controlSequence",JsonPrimitive(10)),
            f.accept.change("controlSequence",JsonPrimitive("9")),f.accept.change("sha256",JsonPrimitive("8".repeat(64))),
            f.accept.change("monotonicNS",JsonPrimitive(1))).forEach { accepted ->
            val raw=String(wire(accepted));val changed=f.permit.change("owner_accept_raw",JsonPrimitive(raw))
                .change("owner_accept_sha256",JsonPrimitive(hash(raw)))
            rejected { f.actor().validatePermit(wire(changed),f.reply,f.witness) }
        }
        rejected { f.actor(f.config.change("owner_ready_sha256",JsonPrimitive("8".repeat(64))))
            .validatePermit(wire(f.permit),f.reply,f.witness) }
    }
    @Test fun malformedDuplicateExtraOrNonCanonicalWireIsRejected() = Fixture().use { f ->
        val raw=String(wire(f.permit))
        listOf(raw.dropLast(1),raw+"\n",raw.replace("\n","\r\n"),"\uFEFF"+raw,
            raw.replaceFirst("{","{\"schema\":\"duplicate\","),String(wire(f.permit.change("extra",JsonPrimitive(true)))))
            .forEach { malformed -> rejected { f.actor().validatePermit(malformed.toByteArray(),f.reply,f.witness) } }
    }
    @Test fun changedNativeWitnessIsRejected() = Fixture().use { f ->
        f.witness.appendText(" ")
        rejected { f.actor().validatePermit(wire(f.permit),f.reply,f.witness) }
    }
    @Test fun exclusiveLatchCannotReplaceCompleteOrPartialMarker() = Fixture().use { f ->
        val path=File(f.directory,"latch.json"); val original="{\"consumed\":true}\n".toByteArray()
        NormalB6SingleSaveActor.exclusive(path,original)
        rejected { NormalB6SingleSaveActor.exclusive(path,"different".toByteArray()) };assertArrayEquals(original,path.readBytes())
        val partial=File(f.directory,"partial.json");partial.writeBytes(byteArrayOf(123))
        rejected { NormalB6SingleSaveActor.exclusive(partial,original) };assertArrayEquals(byteArrayOf(123),partial.readBytes())
    }
    @Test fun unpinnedOrInvalidConfigCannotEnableActor() = Fixture().use { f ->
        rejected { f.actor(f.config.change("expected_local_body_raw",JsonPrimitive(f.body+" "))) }
        rejected { f.actor(f.config.change("expected_accept_control_sequence",JsonPrimitive("9"))) }
        rejected { f.actor(f.config.change("extra",JsonPrimitive(true))) }
    }
}
