package com.example.merchandisecontrolsplitview.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncEventReadBoundaryTest {

    @Test
    fun `139 production client has no direct sync events select or realtime subscription`() {
        val sourceRoot = projectRoot().resolve(
            "app/src/main/java/com/example/merchandisecontrolsplitview"
        )
        assertTrue(sourceRoot.isDirectory)
        val sources = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val forbidden = listOf(
            Regex("postgrest\\s*\\[[^]]*sync_events", RegexOption.IGNORE_CASE),
            Regex("table\\s*=\\s*\\\"sync_events\\\"", RegexOption.IGNORE_CASE),
            Regex("TABLE_NAME\\s*=\\s*\\\"sync_events\\\"", RegexOption.IGNORE_CASE)
        )
        val violations = sources.flatMap { file ->
            forbidden.flatMap { pattern ->
                pattern.findAll(file.readText()).map { match ->
                    "${file.relativeTo(sourceRoot)}:${match.range.first}"
                }.toList()
            }
        }

        assertEquals(emptyList<String>(), violations)
        assertFalse(sources.any { it.name == "SupabaseSyncEventRealtimeSubscriber.kt" })

        val reader = sourceRoot.resolve("data/SupabaseShopSyncReadRemoteDataSource.kt").readText()
        assertTrue(reader.contains("SHOP_SYNC_EVENT_PAGE_RPC"))
        assertTrue(reader.contains("shop_sync_event_page_v1"))
    }

    @Test
    fun `139 legacy direct fetch API fails closed without touching transport`() = runTest {
        val source = SupabaseSyncEventRemoteDataSource(client = null)

        val ownerRead = source.fetchSyncEventsAfter(
            ownerUserId = "10000000-0000-4000-8000-000000000001",
            storeId = null,
            afterId = 0L,
            limit = 250L
        )
        val shopRead = source.fetchSyncEventsAfter(
            ownerUserId = "10000000-0000-4000-8000-000000000001",
            storeId = null,
            shopId = "10000000-0000-4000-8000-000000000002",
            afterId = 0L,
            limit = 250L
        )

        assertEquals(
            "sync_event_direct_read_forbidden",
            (ownerRead.exceptionOrNull() as ShopSyncContractException).code
        )
        assertEquals(
            "sync_event_direct_read_forbidden",
            (shopRead.exceptionOrNull() as ShopSyncContractException).code
        )
    }

    @Test
    fun `139 writer uses strict V6 and does not call legacy after strict success`() = runTest {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val expected = remoteRow(9_007_199_254_740_993L)
        val source = SupabaseSyncEventRemoteDataSource { functionName, payload ->
            calls += functionName to payload
            Result.success(expected)
        }

        val result = source.recordSyncEvent(globalParams())

        assertEquals(expected, result.getOrThrow())
        assertEquals(listOf("record_sync_event_v6"), calls.map { it.first })
    }

    @Test
    fun `139 missing strict V6 falls back once only for a legacy compatible global event`() =
        runTest {
            for (missingCode in listOf("PGRST202", "42883")) {
                val calls = mutableListOf<Pair<String, JsonObject>>()
                val expected = remoteRow(9_007_199_254_740_993L)
                val source = SupabaseSyncEventRemoteDataSource { functionName, payload ->
                    calls += functionName to payload
                    if (functionName == "record_sync_event_v6") {
                        Result.failure(SyncEventRpcCodedException(missingCode))
                    } else {
                        Result.success(expected)
                    }
                }

                val result = source.recordSyncEvent(globalParams())

                assertEquals(expected, result.getOrThrow())
                assertEquals(
                    listOf("record_sync_event_v6", "record_sync_event"),
                    calls.map { it.first }
                )
                assertFalse(calls.last().second.containsKey("p_shop_id"))
            }
        }

    @Test
    fun `139 validation lease and network failures never fall back to legacy`() = runTest {
        for (rpcCode in listOf("42501", "22023")) {
            val calls = mutableListOf<String>()
            val source = SupabaseSyncEventRemoteDataSource { functionName, _ ->
                calls += functionName
                Result.failure(SyncEventRpcCodedException(rpcCode))
            }

            assertTrue(source.recordSyncEvent(globalParams()).isFailure)
            assertEquals(listOf("record_sync_event_v6"), calls)
        }

        val networkCalls = mutableListOf<String>()
        val networkSource = SupabaseSyncEventRemoteDataSource { functionName, _ ->
            networkCalls += functionName
            Result.failure(IOException("offline"))
        }
        assertTrue(networkSource.recordSyncEvent(globalParams()).isFailure)
        assertEquals(listOf("record_sync_event_v6"), networkCalls)
    }

    @Test
    fun `139 shop scoped or non RFC UUID event remains failed when strict V6 is missing`() =
        runTest {
            val incompatible = listOf(
                globalParams().copy(shopId = uuid(4)),
                globalParams().copy(
                    entityIds = SyncEventEntityIds(
                        productIds = listOf("00000000-0000-0000-0000-000000000000")
                    )
                ),
                globalParams().copy(
                    entityIds = SyncEventEntityIds(
                        productIds = listOf("01900000-0000-7000-8000-000000000001")
                    )
                )
            )

            for (params in incompatible) {
                val calls = mutableListOf<String>()
                val source = SupabaseSyncEventRemoteDataSource { functionName, _ ->
                    calls += functionName
                    Result.failure(SyncEventRpcCodedException("PGRST202"))
                }

                assertTrue(source.recordSyncEvent(params).isFailure)
                assertEquals(listOf("record_sync_event_v6"), calls)
            }
        }

    @Test
    fun `139 sync event id decodes exact V6 string and legacy bigint without Double`() {
        val strict = Json.decodeFromString<SyncEventRemoteRow>(
            remoteRowJson("\"9007199254740993\"")
        )
        val legacy = Json.decodeFromString<SyncEventRemoteRow>(
            remoteRowJson("9223372036854775807")
        )

        assertEquals(9_007_199_254_740_993L, strict.id)
        assertEquals(Long.MAX_VALUE, legacy.id)
        try {
            Json.decodeFromString<SyncEventRemoteRow>(remoteRowJson("\"01\""))
            fail("Expected a non-canonical decimal string to fail")
        } catch (_: SerializationException) {
            // Expected fail-closed decode.
        }
    }

    private fun globalParams() = SyncEventRecordRpcParams(
        domain = SyncEventDomains.CATALOG,
        eventType = SyncEventTypes.CATALOG_CHANGED,
        changedCount = 1,
        entityIds = SyncEventEntityIds(productIds = listOf(uuid(1))),
        storeId = uuid(2),
        source = "android",
        sourceDeviceId = "device-fixture",
        batchId = uuid(3),
        clientEventId = "android-fixture-event",
        shopId = null
    )

    private fun remoteRow(id: Long) = SyncEventRemoteRow(
        id = id,
        ownerUserId = uuid(10),
        domain = SyncEventDomains.CATALOG,
        eventType = SyncEventTypes.CATALOG_CHANGED,
        changedCount = 1,
        createdAt = "2026-07-23T12:00:00Z"
    )

    private fun remoteRowJson(id: String): String =
        """
        {
          "id":$id,
          "owner_user_id":"${uuid(10)}",
          "domain":"catalog",
          "event_type":"catalog_changed",
          "changed_count":1,
          "created_at":"2026-07-23T12:00:00Z"
        }
        """.trimIndent()

    private fun uuid(index: Int): String =
        "13900000-0000-4000-8000-${index.toString().padStart(12, '0')}"

    private fun projectRoot(): File {
        var current: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (current != null) {
            if (current.resolve("app/src/main/java").isDirectory) return current
            current = current.parentFile
        }
        error("Android project root not found")
    }
}
