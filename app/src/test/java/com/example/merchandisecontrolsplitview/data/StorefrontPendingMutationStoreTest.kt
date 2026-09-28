package com.example.merchandisecontrolsplitview.data

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorefrontPendingMutationStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `shared semantic fixture round trips durable canonical payloads`() {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/mobile-storefront-intent-parity-v1.json"))
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        for (case in fixture.getAsJsonArray("cases")) {
            val value = case.asJsonObject
            val draftJson = value.getAsJsonObject("draft")
            val draft = StorefrontEditorDraft(
                publicName = draftJson["publicName"].asString,
                publicDescription = draftJson["publicDescription"].asString,
                publicBrand = draftJson["publicBrand"].asString,
                publicPrice = draftJson["publicPrice"].asLong,
                compareAtPrice = draftJson["compareAtPrice"].asLong
            )
            val record = record().copy(draft = draft, desiredDraft = draft, expectedVersion = value["expectedVersion"].asLong)
            FileStorefrontPendingMutationStore(temporary.root).write(record)
            val restored = FileStorefrontPendingMutationStore(temporary.root).read(ACCOUNT, SHOP, PRODUCT)
            assertEquals(record, restored)
            val payload = storefrontMutationPayload(PRODUCT, requireNotNull(restored).operation, restored.draft)
            assertEquals(draftJson["publicName"].asString, payload["publicName"].toString().trim('"'))
            assertEquals(draft.publicPrice.toString(), payload["publicPrice"].toString())
        }
    }

    @Test fun `new storage instances keep all products beyond UI cache size and isolate scope`() {
        val store = FileStorefrontPendingMutationStore(temporary.root)
        repeat(250) { index ->
            val id = "dddddddd-dddd-dddd-dddd-${index.toString().padStart(12, '0')}"
            store.write(record().copy(remoteProductId = id))
        }
        val fresh = FileStorefrontPendingMutationStore(temporary.root)
        assertNotNull(fresh.read(ACCOUNT, SHOP, "dddddddd-dddd-dddd-dddd-000000000000"))
        assertNull(fresh.read(SHOP, ACCOUNT, "dddddddd-dddd-dddd-dddd-000000000000"))
        assertEquals(250, temporary.root.listFiles()?.size)
    }

    @Test fun `write failure is propagated and corrupt read fails closed`() {
        val nonDirectory = temporary.newFile("blocked")
        assertThrows(Exception::class.java) { FileStorefrontPendingMutationStore(nonDirectory).write(record()) }
        val dir = temporary.newFolder("valid")
        val store = FileStorefrontPendingMutationStore(dir)
        store.write(record())
        requireNotNull(dir.listFiles()).single().writeText("{broken")
        assertThrows(Exception::class.java) { store.read(ACCOUNT, SHOP, PRODUCT) }
        store.write(record())
        val saved = requireNotNull(dir.listFiles()).single()
        saved.writeText(saved.readText().replace("QUEUED", "UNKNOWN_FORMAT_STATE"))
        assertThrows(Exception::class.java) { store.read(ACCOUNT, SHOP, PRODUCT) }
    }

    @Test fun `intent removal never affects another scope`() {
        val store = FileStorefrontPendingMutationStore(temporary.root)
        store.write(record())
        store.write(record().copy(shopId = ACCOUNT))
        store.remove(ACCOUNT, SHOP, PRODUCT)
        assertNull(store.read(ACCOUNT, SHOP, PRODUCT))
        assertNotNull(FileStorefrontPendingMutationStore(temporary.root).read(ACCOUNT, ACCOUNT, PRODUCT))
    }

    @Test fun `measure isolated durable write and reload latency`() {
        val store = FileStorefrontPendingMutationStore(temporary.root)
        val writeSamples = mutableListOf<Double>()
        val readSamples = mutableListOf<Double>()
        repeat(43) { index ->
            val value = record().copy(expectedVersion = index.toLong())
            val start = System.nanoTime()
            store.write(value)
            val written = System.nanoTime()
            assertEquals(value, FileStorefrontPendingMutationStore(temporary.root).read(ACCOUNT, SHOP, PRODUCT))
            val read = System.nanoTime()
            if (index >= 3) {
                writeSamples += (written - start) / 1_000_000.0
                readSamples += (read - written) / 1_000_000.0
            }
        }
        fun summary(samples: List<Double>): String {
            val sorted = samples.sorted()
            return "n=${sorted.size} p50_ms=${sorted[19]} p95_ms=${sorted[37]} max_ms=${sorted.last()}"
        }
        println("STOREFRONT_STORAGE_BENCH host JVM synthetic record, fsync+atomic replace, 3 warmups; write ${summary(writeSamples)}; fresh-read ${summary(readSamples)}")
    }

    private fun record() = StorefrontPendingMutation(
        accountId = ACCOUNT, shopId = SHOP, remoteProductId = PRODUCT,
        operation = StorefrontMutationOperation.SAVE_DRAFT,
        draft = StorefrontEditorDraft(publicName = "茶", publicPrice = 12990),
        baseDraft = StorefrontEditorDraft(), basePublication = null,
        expectedVersion = 0, idempotencyKey = KEY
    )
    private companion object {
        const val ACCOUNT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val SHOP = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val PRODUCT = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val KEY = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
    }
}
