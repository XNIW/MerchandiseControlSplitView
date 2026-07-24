package com.example.merchandisecontrolsplitview.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ABI regressions for the frozen TASK-139 V6 RPC contract.  These tests are
 * deliberately wire-oriented: a serializer that drops a fence must fail here
 * before it can turn a server divergence into a client noWork result.
 */
class SupabaseShopSyncReadRemoteDataSourceTest {

    @Test
    fun `resource-limit overrides can narrow but never widen V6 domain caps`() {
        listOf(
            { DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(supplierPageRows = 241) },
            { DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(categoryPageRows = 241) },
            { DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(productPageRows = 61) },
            { DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(pricePageRows = 121) },
            { DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(historyPageRows = 4) },
            { DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(imagePageRows = 241) }
        ).forEach { construct ->
            try {
                construct()
                fail("V6 cap override must be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected: callers may only narrow a negotiated domain cap.
            }
        }
        assertEquals(
            59,
            DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(productPageRows = 59)
                .pageRows(ShopSyncRowDomain.PRODUCTS)
        )
    }

    @Test
    fun `recovery-only price canonical is absent from ordinary price upsert payload`() {
        val recoveryRow = InventoryProductPriceRow(
            id = PRODUCT_ID,
            ownerUserId = ACCOUNT_ID,
            shopId = SHOP_ID,
            productId = PRODUCT_ID,
            type = "RETAIL",
            price = 7.0,
            priceCanonical = "7",
            effectiveAt = "2026-07-21 10:00:00",
            source = "REMOTE",
            createdAt = "2026-07-21 10:00:00",
            updatedAt = "2026-07-21T10:00:00.000000Z"
        )

        val payload = Json.Default.encodeToJsonElement(
            InventoryProductPriceWriteRow.serializer(),
            recoveryRow.toWriteRow()
        ).jsonObject

        assertFalse(payload.containsKey("price_canonical"))
        assertFalse(payload.containsKey("updated_at"))
        assertEquals("7", recoveryRow.priceCanonical)
        assertEquals("RETAIL", payload.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `recovery reader rejects UUIDv7 and nil entity cursors before invoking RPC`() = runTest {
        listOf(POSTGRES_UUID_V7, POSTGRES_NIL_UUID).forEach { remoteId ->
            val invoker = RecordingInvoker { _, _ -> error("RPC must not receive unsupported entity ID") }
            val source = SupabaseShopSyncReadRemoteDataSource(invoker)

            val page = source.recoveryPage(
                fencedContext(ShopSyncRowDomain.PRODUCTS),
                ShopSyncRowDomain.PRODUCTS,
                afterId = remoteId,
                limit = 60
            )
            assertEquals(
                "page_cursor_invalid",
                (page.exceptionOrNull() as ShopSyncContractException).code
            )
            assertTrue(invoker.calls.isEmpty())

            val targeted = source.rowsByIds(
                fencedContext(ShopSyncRowDomain.PRODUCTS),
                ShopSyncRowDomain.PRODUCTS,
                listOf(remoteId)
            )
            assertEquals(
                "targeted_id_invalid",
                (targeted.exceptionOrNull() as ShopSyncContractException).code
            )
            assertTrue(invoker.calls.isEmpty())
        }
    }

    @Test
    fun `v6 checkpoint and marker send canonical baseline and opaque scope key`() = runTest {
        val invoker = RecordingInvoker { function, _ ->
            when (function) {
                "shop_sync_recovery_checkpoint_v1" -> checkpointJson(
                    maxId = "9007199254740993",
                    baseline = "42"
                )
                "shop_sync_convergence_marker_v1" -> markerJson(
                    maxId = "9007199254740993",
                    baseline = "9007199254740993"
                )
                else -> error("unexpected rpc $function")
            }
        }
        val source = SupabaseShopSyncReadRemoteDataSource(invoker)
        val scope = scope()
        val checkpoint = source.checkpoint(
            context(
                expectedScope = scope,
                baseline = "42",
                baselineScopeKey = scope.key
            )
        ).getOrThrow()
        assertEquals("9007199254740993", checkpoint.syncEvents.maxId)
        assertEquals(9_007_199_254_740_993L, parseShopSyncMaxEventId(checkpoint.syncEvents.maxId))

        val checkpointParams = invoker.calls.first().second
        assertEquals(
            setOf(
                "p_shop_id",
                "p_device_identifier",
                "p_verified_baseline_id",
                "p_expected_baseline_scope_key"
            ),
            checkpointParams.keys
        )
        assertEquals("42", checkpointParams.getValue("p_verified_baseline_id").jsonPrimitive.content)
        assertEquals(SCOPE_KEY, checkpointParams.getValue("p_expected_baseline_scope_key").jsonPrimitive.content)

        val marker = source.convergenceMarker(
            context(
                expectedScope = scope,
                baseline = "9007199254740993",
                baselineScopeKey = scope.key
            )
        ).getOrThrow()
        assertTrue(marker.serverNoWorkEligible)
        assertEquals("shop_sync_convergence_marker_v1", invoker.calls.last().first)
        assertEquals(
            "9007199254740993",
            invoker.calls.last().second.getValue("p_verified_baseline_id").jsonPrimitive.content
        )
    }

    @Test
    fun `v6 reader rejects a scope key bound to another account or device`() = runTest {
        val wrongAccount = scope(accountKey = "a".repeat(64))
        val wrongDevice = scope(deviceKey = "b".repeat(64))

        val accountResult = SupabaseShopSyncReadRemoteDataSource(
            RecordingInvoker { _, _ -> checkpointJson(scope = wrongAccount) }
        ).checkpoint(context())
        val deviceResult = SupabaseShopSyncReadRemoteDataSource(
            RecordingInvoker { _, _ -> checkpointJson(scope = wrongDevice) }
        ).checkpoint(context())

        assertEquals(
            "scope_account_identity_mismatch",
            (accountResult.exceptionOrNull() as ShopSyncContractException).code
        )
        assertEquals(
            "scope_device_identity_mismatch",
            (deviceResult.exceptionOrNull() as ShopSyncContractException).code
        )
    }

    @Test
    fun `v6 recovery page sends all fences and clamps product page to sixty`() = runTest {
        val invoker = RecordingInvoker { function, _ ->
            assertEquals("shop_sync_recovery_page_v1", function)
            recoveryPageJson(domain = "products", limit = 60)
        }
        val source = SupabaseShopSyncReadRemoteDataSource(invoker)

        source.recoveryPage(
            context = fencedContext(ShopSyncRowDomain.PRODUCTS),
            domain = ShopSyncRowDomain.PRODUCTS,
            afterId = null,
            limit = 250
        ).getOrThrow()

        val params = invoker.calls.single().second
        assertEquals(60, params.getValue("p_limit").jsonPrimitive.content.toInt())
        assertEquals(SCOPE_KEY, params.getValue("p_expected_scope_key").jsonPrimitive.content)
        assertEquals("42", params.getValue("p_expected_event_max_id").jsonPrimitive.content)
        assertEquals("42", params.getValue("p_expected_domain_event_max_id").jsonPrimitive.content)
        assertTrue(params.containsKey("p_after_id"))
    }

    @Test
    fun `v6 targeted calls require fences and enforce history cap three`() = runTest {
        val neverCalled = RecordingInvoker { _, _ -> error("must not call") }
        val source = SupabaseShopSyncReadRemoteDataSource(neverCalled)
        val tooMany = source.rowsByIds(
            fencedContext(ShopSyncRowDomain.HISTORY),
            ShopSyncRowDomain.HISTORY,
            listOf(PRODUCT_ID, SUPPLIER_ID, MISSING_ID, CATEGORY_ID)
        )
        assertEquals("targeted_ids_count_invalid", (tooMany.exceptionOrNull() as ShopSyncContractException).code)
        assertTrue(neverCalled.calls.isEmpty())

        val invoker = RecordingInvoker { function, _ ->
            assertEquals("shop_sync_rows_by_ids_v1", function)
            targetedJson(domain = "history", requestedIds = listOf(PRODUCT_ID))
        }
        SupabaseShopSyncReadRemoteDataSource(invoker).rowsByIds(
            fencedContext(ShopSyncRowDomain.HISTORY),
            ShopSyncRowDomain.HISTORY,
            listOf(PRODUCT_ID)
        ).getOrThrow()
        val params = invoker.calls.single().second
        assertEquals("42", params.getValue("p_expected_event_max_id").jsonPrimitive.content)
        assertEquals("42", params.getValue("p_expected_domain_event_max_id").jsonPrimitive.content)
    }

    @Test
    fun `v6 event page uses string bigint ids bootstrap fence and continuation fence`() = runTest {
        val invoker = RecordingInvoker { function, params ->
            assertEquals("shop_sync_event_page_v1", function)
            if (params.getValue("p_after_id").jsonPrimitive.content == "0") {
                eventPageJson(id = "9007199254740993", asOf = "9007199254740993")
            } else {
                eventPageJson(id = null, asOf = "9007199254740993")
            }
        }
        val source = SupabaseShopSyncReadRemoteDataSource(invoker)
        val first = source.eventPage(
            context = context(expectedScope = scope()),
            afterId = 0L,
            limit = 150
        ).getOrThrow()
        assertEquals(9_007_199_254_740_993L, first.rows.single().id)
        val firstParams = invoker.calls.first().second
        assertEquals("0", firstParams.getValue("p_after_id").jsonPrimitive.content)
        assertEquals("null", firstParams.getValue("p_expected_event_max_id").toString())
        assertEquals(150, firstParams.getValue("p_limit").jsonPrimitive.content.toInt())

        source.eventPage(
            context = context(expectedScope = scope(), eventMax = first.asOfEventMaxId),
            afterId = first.rows.single().id,
            limit = 150
        ).getOrThrow()
        assertEquals(
            "9007199254740993",
            invoker.calls.last().second.getValue("p_expected_event_max_id").jsonPrimitive.content
        )
    }

    @Test
    fun `v6 event page rejects numeric ids before any local long conversion`() = runTest {
        val source = SupabaseShopSyncReadRemoteDataSource(
            RecordingInvoker { _, _ -> eventPageJson(id = "1", numericId = true) }
        )
        val result = source.eventPage(context(expectedScope = scope()), 0L, 1)
        assertTrue(result.isFailure)
    }

    @Test
    fun `authorized shop plus legacy accepts only allowed legacy rows and history kind`() = runTest {
        val legacyOwner = "00000000-0000-4000-8000-000000000777"
        val compoundScope = scope(
            kind = ShopSyncScopeKinds.AUTHORIZED_SHOP_PLUS_LEGACY,
            historyKind = ShopSyncScopeKinds.AUTHORIZED_SHOP_PLUS_LEGACY,
            legacyOwnerKey = sha256(legacyOwner.lowercase())
        )
        val invoker = RecordingInvoker { _, _ ->
            recoveryPageJson(
                domain = "history",
                limit = 3,
                scope = compoundScope,
                rows = """[{"remote_id":"$PRODUCT_ID","payload_version":1,"timestamp":"2026-07-22 03:04:05","supplier":"S","category":"C","is_manual_entry":false,"data":[["x"]],"owner_user_id":"$legacyOwner","shop_id":null,"updated_at":"2026-07-22T03:04:05.123456Z","deleted_at":null}]"""
            )
        }
        val result = SupabaseShopSyncReadRemoteDataSource(invoker).recoveryPage(
            fencedContext(ShopSyncRowDomain.HISTORY, compoundScope),
            ShopSyncRowDomain.HISTORY,
            null,
            3
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `v6 page and targeted calls fail closed locally when a fence is missing`() = runTest {
        val invoker = RecordingInvoker { _, _ -> error("must not call") }
        val source = SupabaseShopSyncReadRemoteDataSource(invoker)
        val page = source.recoveryPage(context(expectedScope = scope()), ShopSyncRowDomain.PRODUCTS, null, 60)
        val rows = source.rowsByIds(
            context(expectedScope = scope()),
            ShopSyncRowDomain.PRODUCTS,
            listOf(PRODUCT_ID)
        )
        assertEquals("page_event_fence_missing", (page.exceptionOrNull() as ShopSyncContractException).code)
        assertEquals("page_event_fence_missing", (rows.exceptionOrNull() as ShopSyncContractException).code)
        assertTrue(invoker.calls.isEmpty())
    }

    @Test
    fun `shared v6 fixture freezes caps wire strings and image read batch`() {
        val fixture = Json.parseToJsonElement(contractFile().readText()).jsonObject
        assertEquals(6, fixture.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals(150, fixture.getValue("eventPageLimit").jsonPrimitive.content.toInt())
        assertEquals(60, fixture.getValue("recoveryCaps").jsonObject
            .getValue("products").jsonPrimitive.content.toInt())
        assertEquals(3, fixture.getValue("targetedCaps").jsonObject
            .getValue("history").jsonPrimitive.content.toInt())
        assertEquals(16, fixture.getValue("imageReadUrlsMaxRefs").jsonPrimitive.content.toInt())
        assertEquals("0|[1-9][0-9]{0,18}", fixture.getValue("canonicalEventIdPattern").jsonPrimitive.content)
    }

    private fun context(
        expectedScope: ShopSyncScope? = null,
        baseline: String = "0",
        baselineScopeKey: String? = null,
        eventMax: String? = null,
        domainMax: String? = null
    ) = ShopSyncRpcContext(
        accountId = ACCOUNT_ID,
        shopId = SHOP_ID,
        deviceIdentifier = DEVICE_ID,
        expectedScope = expectedScope,
        verifiedBaselineId = baseline,
        expectedBaselineScopeKey = baselineScopeKey,
        expectedEventMaxId = eventMax,
        expectedDomainEventMaxId = domainMax
    )

    private fun fencedContext(
        domain: ShopSyncRowDomain,
        scope: ShopSyncScope = scope()
    ): ShopSyncRpcContext = context(
        expectedScope = scope,
        eventMax = "42",
        domainMax = "42"
    )

    private fun scope(
        kind: String = ShopSyncScopeKinds.SHOP_SCOPED,
        historyKind: String = ShopSyncScopeKinds.SHOP_SCOPED,
        legacyOwnerKey: String? = null,
        accountKey: String = ACCOUNT_KEY,
        deviceKey: String = DEVICE_KEY
    ) = ShopSyncScope(
        kind = kind,
        key = SCOPE_KEY,
        legacyOwnerKey = legacyOwnerKey,
        historyKind = historyKind,
        accountKey = accountKey,
        deviceKey = deviceKey
    )

    private fun checkpointJson(
        maxId: String = "42",
        baseline: String = "0",
        scope: ShopSyncScope = scope()
    ): JsonObject = parseObject(
        """
        {"schemaVersion":"shop-sync-recovery-checkpoint-v1","status":"ready","shopId":"$SHOP_ID",
         "scope":${scopeJson(scope)},"syncEvents":${syncEventsJson(maxId, baseline)},
         "catalog":{"suppliers":${domainJson()},"categories":${domainJson()},"products":${domainJson(identity = true)},"digest":"$DIGEST"},
         "prices":${domainJson()},"history":${domainJson()},"images":${domainJson()},
         "integrity":{"productCategoryViolationCount":0,"productSupplierViolationCount":0,"priceProductViolationCount":0,"primaryImageViolationCount":0,"historyIdViolationCount":0,"totalViolationCount":0},"checkpointDigest":"$DIGEST"}
        """
    )

    private fun markerJson(maxId: String, baseline: String): JsonObject = parseObject(
        """
        {"schemaVersion":"shop-sync-convergence-marker-v1","status":"ready","shopId":"$SHOP_ID",
         "scope":${scopeJson()},"syncEvents":${syncEventsJson(maxId, baseline, requiresFullRecovery = false)},
         "catalog":{"suppliers":${domainJson()},"categories":${domainJson()},"products":${domainJson(identity = true)},"digest":"$DIGEST"},
         "prices":${domainJson()},"history":${domainJson()},"images":${domainJson()},
         "integrity":{"totalViolationCount":0},"checkpointDigest":"$DIGEST","serverNoWorkEligible":true,"markerDigest":"$MARKER_DIGEST"}
        """
    )

    private fun recoveryPageJson(
        domain: String,
        limit: Int,
        scope: ShopSyncScope = scope(),
        rows: String = "[]"
    ): JsonObject = parseObject(
        """
        {"schemaVersion":"shop-sync-recovery-page-v1","shopId":"$SHOP_ID","scope":${scopeJson(scope)},"domain":"$domain",
         "snapshotEventMaxId":"42","currentScopeEventMaxId":"42","baselineDomainEventMaxId":"42","pageDomainEventMaxId":"42",
         "domainScope":"${if (domain == "history") scope.historyKind else scope.kind}","pageLimit":$limit,"rows":$rows,"nextAfterId":null,"hasMore":false}
        """
    )

    private fun targetedJson(domain: String, requestedIds: List<String>): JsonObject = parseObject(
        """
        {"schemaVersion":"shop-sync-rows-by-ids-v1","shopId":"$SHOP_ID","scope":${scopeJson()},"domain":"$domain",
         "asOfEventMaxId":"42","currentScopeEventMaxId":"42","minimumDomainEventMaxId":"42","materializedDomainEventMaxId":"42","domainScope":"shop_scoped",
         "requestedCount":${requestedIds.size},"rows":[],"missingIds":${requestedIds.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}}
        """
    )

    private fun eventPageJson(
        id: String?,
        asOf: String = "1",
        numericId: Boolean = false
    ): JsonObject {
        val rows = if (id == null) {
            "[]"
        } else {
            val encodedId = if (numericId) id else "\"$id\""
            """[{"id":$encodedId,"owner_user_id":"$ACCOUNT_ID","shop_id":"$SHOP_ID","authorized_shop_id":"$SHOP_ID","store_id":null,"domain":"catalog","event_type":"catalog_changed","source":"admin_web","source_device_key":null,"changed_count":0,"entity_ids":null,"requires_full_recovery":false,"timestamp_valid":true,"created_at":"2026-07-22T03:04:05.123456Z","metadata":{}}]"""
        }
        return parseObject(
            """
            {"schemaVersion":"shop-sync-event-page-v1","shopId":"$SHOP_ID","scope":${scopeJson()},"scopeEventMaxId":"$asOf","asOfEventMaxId":"$asOf",
             "asOfDomainEventMaxIds":{"catalog":"$asOf","prices":"$asOf","history":"$asOf"},"pageLimit":150,"rows":$rows,"nextAfterId":null,"hasMore":false}
            """
        )
    }

    private fun syncEventsJson(
        maxId: String,
        baseline: String,
        requiresFullRecovery: Boolean = false
    ) = """{"maxId":"$maxId","verifiedBaselineId":"$baseline","requiresFullRecovery":$requiresFullRecovery,"domainMaxIds":{"catalog":"$maxId","prices":"$maxId","history":"$maxId"}}"""

    private fun domainJson(identity: Boolean = false) =
        """{"activeCount":0,"tombstoneCount":0,"idSetDigest":"$DIGEST","versionDigest":"$DIGEST"${if (identity) ",\"identityDigest\":\"$DIGEST\"" else ""}}"""

    private fun scopeJson(scope: ShopSyncScope = scope()) =
        """{"kind":"${scope.kind}","historyKind":"${scope.historyKind}","key":"${scope.key}","legacyOwnerKey":${scope.legacyOwnerKey?.let { "\"$it\"" } ?: "null"},"accountKey":"${scope.accountKey}","deviceKey":"${scope.deviceKey}"}"""

    private fun parseObject(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private fun contractFile(): File {
        var current: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (current != null) {
            val candidate = current.resolve("contracts/fixtures/task139-sync-recovery-v6.json")
            if (candidate.isFile) return candidate
            current = current.parentFile
        }
        error("task139 sync V6 fixture not found")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private class RecordingInvoker(
        private val response: suspend (String, JsonObject) -> JsonObject
    ) : ShopSyncRpcInvoker {
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun call(
            function: String,
            params: JsonObject,
            maximumResponseBytes: Long,
            maximumHistoryRowBytes: Long?
        ): ShopSyncRpcResponse {
            calls += function to params
            val payload = response(function, params)
            return ShopSyncRpcResponse(payload, payload.toString().encodeToByteArray().size.toLong())
        }
    }

    private companion object {
        const val ACCOUNT_ID = "00000000-0000-4000-8000-000000000139"
        const val SHOP_ID = "00000000-0000-4000-8000-000000000140"
        const val PRODUCT_ID = "00000000-0000-4000-8000-000000000141"
        const val SUPPLIER_ID = "00000000-0000-4000-8000-000000000142"
        const val CATEGORY_ID = "00000000-0000-4000-8000-000000000143"
        const val MISSING_ID = "00000000-0000-4000-8000-000000000144"
        const val POSTGRES_UUID_V7 = "018f0ad4-77f2-7c9d-a8be-4f6b9d234567"
        const val POSTGRES_NIL_UUID = "00000000-0000-0000-0000-000000000000"
        const val DEVICE_ID = "device-139"
        const val DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
        const val MARKER_DIGEST = "2222222222222222222222222222222222222222222222222222222222222222"
        const val SCOPE_KEY = "1111111111111111111111111111111111111111111111111111111111111111"
        const val ACCOUNT_KEY = "2306c87344a194ed9a6a1fb2949a03c5608c6b2755b4943f6cdbd661b0a275b9"
        const val DEVICE_KEY = "257e184cfeb7888e6eb749b3ca4b2d644ef4f278bde892714196a3980ded96e6"
    }
}
