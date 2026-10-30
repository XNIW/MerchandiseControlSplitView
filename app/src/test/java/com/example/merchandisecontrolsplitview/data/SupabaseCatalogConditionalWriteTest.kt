package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.engine.okhttp.OkHttp
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** Real SDK requests captured before DNS/connect; this does not replace TEST backend acceptance. */
class SupabaseCatalogConditionalWriteTest {
    @Test
    fun conditionalPatchKeepsLosslessVersionScopeAndExplicitNullWithoutUpsert() = runBlocking {
        withFixture { fixture ->
            val result = fixture.patch()
            assertTrue(result.isSuccess)
            assertEquals(emptyList<InventoryProductRow>(), result.getOrThrow())
            val request = fixture.requests.single()
            assertEquals("PATCH", request.method)
            assertEquals("/rest/v1/inventory_products", request.url.encodedPath)
            assertEquals("eq.$PRODUCT", request.url.queryParameter("id"))
            assertEquals("eq.$OWNER", request.url.queryParameter("owner_user_id"))
            assertEquals("eq.$SHOP", request.url.queryParameter("shop_id"))
            assertEquals("eq.$VERSION", request.url.queryParameter("updated_at"))
            assertEquals("is.null", request.url.queryParameter("deleted_at"))
            assertTrue(request.header("Prefer").orEmpty().contains("return=representation"))
            assertFalse(request.header("Accept").orEmpty().contains("vnd.pgrst.object"))
            val buffer = Buffer()
            requireNotNull(request.body).writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals(setOf("product_name", "second_product_name"), body.keys)
            assertEquals("Chosen name", body.getValue("product_name").jsonPrimitive.content)
            assertEquals(JsonNull, body.getValue("second_product_name"))
            assertEquals("Zero rows must not cause an upsert or another write", 1, fixture.requests.size)
        }
    }

    @Test
    fun conditionalPatchReturnsQualifiedRowAndPreservesNonNullPredicateSeparately() = runBlocking {
        withFixture { fixture ->
            fixture.response = Json.encodeToString(listOf(row()))
            val result = fixture.patch()
            assertTrue(result.toString(), result.isSuccess)
            assertEquals("2026-10-23T12:34:57.987654+00:00", result.getOrThrow().single().updatedAt)
            fixture.response = "[]"
            assertEquals(emptyList<InventoryProductRow>(), fixture.patch(deletedAt = TOMBSTONE).getOrThrow())
            assertEquals("eq.$TOMBSTONE", fixture.requests.last().url.queryParameter("deleted_at"))
        }
    }

    @Test
    fun conditionalPatchBindsLegacyNullShopExplicitly() = runBlocking {
        withFixture { fixture ->
            assertTrue(fixture.patch(shopId = null).isSuccess)
            assertEquals("is.null", fixture.requests.single().url.queryParameter("shop_id"))
            assertEquals("eq.$OWNER", fixture.requests.single().url.queryParameter("owner_user_id"))
        }
    }

    @Test
    fun conditionalPatchRejectsMultipleRowsForeignIdentityAndMissingVersion() = runBlocking {
        withFixture { fixture ->
            listOf(
                listOf(row(), row()), listOf(row().copy(id = FOREIGN)),
                listOf(row().copy(ownerUserId = FOREIGN)), listOf(row().copy(shopId = FOREIGN)),
                listOf(row().copy(updatedAt = null)), listOf(row().copy(deletedAt = TOMBSTONE))
            ).forEach { rows ->
                fixture.response = Json.encodeToString(rows)
                assertTrue("Unqualified response accepted: $rows", fixture.patch().isFailure)
            }
            assertEquals(6, fixture.requests.size)
        }
    }

    @Test
    fun conditionalPatchRejectsMismatchedSelectedValuesIncludingNull() = runBlocking {
        withFixture { fixture ->
            listOf(row().copy(productName = "Different"), row().copy(secondProductName = "Not cleared"))
                .forEach { returned ->
                    fixture.response = Json.encodeToString(listOf(returned))
                    assertTrue("Selected values did not match the submitted body", fixture.patch().isFailure)
                }
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test
    fun conditionalPatchRejectsInvalidMaskAndVersionBeforeSending() = runBlocking {
        withFixture { fixture ->
            listOf(emptySet(), setOf("unknown"), setOf("tombstone"), setOf("barcode"))
                .forEach { fields ->
                    assertTrue(fixture.patch(patch = InventoryProductPatch(changedFields = fields)).isFailure)
                }
            assertTrue(fixture.patch(version = "").isFailure)
            assertTrue(fixture.patch(owner = "").isFailure)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun conditionalPatchPreservesConstraintAndMalformedResponseFailures() = runBlocking {
        withFixture { fixture ->
            fixture.status = 409
            fixture.response = """{"code":"23505","message":"synthetic constraint rejection","details":null,"hint":null}"""
            val rejection = fixture.patch().exceptionOrNull()
            assertTrue(rejection is PostgrestRestException)
            assertEquals("23505", (rejection as PostgrestRestException).code)
            fixture.status = 200
            fixture.response = "malformed response after potentially committed write"
            assertTrue(fixture.patch().isFailure)
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test
    fun guardedConditionalPatchNeverDispatchesForRevokedDeviceAndForwardsExactContractWhenActive() = runBlocking {
        withFixture { fixture ->
            var active = false
            var beforeStatus: () -> Unit = {}
            val checkedShops = mutableListOf<String>()
            val device = object : ShopDeviceRegistrationRemote {
                override val isConfigured = true
                override suspend fun registerCurrentOwnerDevice(reason: String): Result<ShopDeviceRegistrationResult> =
                    Result.failure(UnsupportedOperationException("Registration is outside this test"))
                override suspend fun currentOwnerDeviceStatus(reason: String): Result<ShopDeviceAuthorizationSnapshot> =
                    Result.failure(UnsupportedOperationException("Explicit shop is required"))
                override suspend fun shopDeviceStatusForShop(shopId: String, reason: String): Result<ShopDeviceAuthorizationSnapshot> {
                    beforeStatus()
                    checkedShops += shopId
                    return Result.success(ShopDeviceAuthorizationSnapshot(
                        status = if (active) "active" else "revoked", code = "test_device_status", canWrite = active,
                        serverTime = null, lastSeenAt = null, reasonCode = "test_device_status",
                        recommendedAction = if (active) "none" else "contact_shop_admin", checkedAtMs = 1_000L))
                }
            }
            val guarded = DeviceGuardedCatalogRemoteDataSource(
                SupabaseCatalogRemoteDataSource(fixture.sdk), ShopDeviceAuthorizationRepository(device))
            val patch = InventoryProductPatch(setOf("productname", "secondproductname"), productName = "Chosen name")
            val denied = guarded.patchProductConditionally(PRODUCT, OWNER, SHOP, patch, VERSION, null)
            assertTrue(denied.exceptionOrNull() is ShopDeviceAuthorizationBlockedException)
            assertTrue(fixture.requests.isEmpty())
            active = true
            fixture.response = Json.encodeToString(listOf(row()))
            val accepted = guarded.patchProductConditionally(PRODUCT, OWNER, SHOP, patch, VERSION, null).getOrThrow()
            assertEquals(listOf(row()), accepted)
            assertEquals(listOf(SHOP, SHOP), checkedShops)
            assertEquals("eq.$VERSION", fixture.requests.single().url.queryParameter("updated_at"))
            assertEquals("is.null", fixture.requests.single().url.queryParameter("deleted_at"))
            val mutableMask = mutableSetOf("productname", "secondproductname")
            beforeStatus = { mutableMask.clear(); mutableMask += "barcode" }
            val beforeAuthorization = patch.copy(changedFields = mutableMask)
            assertEquals(listOf(row()), guarded.patchProductConditionally(
                PRODUCT, OWNER, SHOP, beforeAuthorization, VERSION, null).getOrThrow())
            val body = Buffer().also { requireNotNull(fixture.requests.last().body).writeTo(it) }.readUtf8()
            assertEquals(setOf("product_name", "second_product_name"), Json.parseToJsonElement(body).jsonObject.keys)
            assertEquals(listOf(SHOP, SHOP, SHOP), checkedShops)
        }
    }

    private fun row() = InventoryProductRow(
        id = PRODUCT, ownerUserId = OWNER, shopId = SHOP, barcode = "R4_SYNTHETIC",
        productName = "Chosen name", updatedAt = "2026-10-23T12:34:57.987654+00:00"
    )

    @OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)
    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        val sdk = createSupabaseClient("http://127.0.0.1:1", "synthetic-public-key") {
            httpEngine = OkHttp.create {
                config {
                    addInterceptor { chain ->
                        val request = chain.request()
                        fixture.requests.add(request)
                        okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(fixture.status).message("Synthetic")
                            .body(fixture.response.toResponseBody("application/json".toMediaType())).build()
                    }
                }
            }
            coroutineDispatcher = Dispatchers.IO
            defaultLogLevel = LogLevel.NONE
            requestTimeout = 3.seconds
            install(Auth) {
                autoLoadFromStorage = false
                autoSetupPlatform = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Postgrest) { timeout = 3.seconds }
        }
        fixture.sdk = sdk
        try {
            sdk.auth.importSession(UserSession(
                accessToken = "synthetic-access", refreshToken = "synthetic-refresh", expiresIn = 86_400,
                tokenType = "bearer", user = UserInfo(aud = "authenticated", id = OWNER),
                expiresAt = Clock.System.now() + 1.days), autoRefresh = false)
            withTimeout(5_000L) { block(fixture) }
        } finally { sdk.close() }
    }

    private class Fixture {
        lateinit var sdk: SupabaseClient
        @Volatile var response = "[]"
        @Volatile var status = 200
        val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
        suspend fun patch(
            shopId: String? = SHOP,
            deletedAt: String? = null,
            version: String = VERSION,
            owner: String = OWNER,
            patch: InventoryProductPatch = InventoryProductPatch(
                changedFields = setOf("productname", "secondproductname"), productName = "Chosen name")
        ) = SupabaseCatalogRemoteDataSource(sdk).patchProductConditionally(
            PRODUCT, owner, shopId, patch, version, deletedAt)
    }

    private companion object {
        const val OWNER = "00000000-0000-4000-8000-000000000145"
        const val SHOP = "00000000-0000-4000-8000-000000000146"
        const val PRODUCT = "00000000-0000-4000-8000-000000000147"
        const val FOREIGN = "00000000-0000-4000-8000-000000000148"
        const val VERSION = "2026-10-23T12:34:56.123456+00:00"
        const val TOMBSTONE = "2026-10-23T12:34:55.654321+00:00"
    }
}
