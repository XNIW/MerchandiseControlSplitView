package com.example.merchandisecontrolsplitview.data

import org.junit.Assert.*
import org.junit.Test

/** Pure metadata codec/policy tests; no HTTP engine, logcat, response body, auth or filesystem. */
class ShopSyncOrdinaryHttpFailureMetadataTest {
    private val testProject = "42a5d0119a30cb5f291bff1912a46e1092c77b483bbfed663785ef165260c842"
    private val checkpoint = "shop_sync_recovery_checkpoint_v1"
    private val page = "shop_sync_recovery_page_v1"
    private val marker = "shop_sync_convergence_marker_v1"
    private fun identifier(values: List<String>?) = ShopSyncOrdinaryHttpFailureMetadata.identifier(values)
    private fun proxy(values: List<String>?) = ShopSyncOrdinaryHttpFailureMetadata.proxyStatus(values)
    private fun message(status: Int = 500, lookup: (String)->List<String>? = { null }) =
        ShopSyncOrdinaryHttpFailureMetadata.message(checkpoint,status,19757,1000,2000,1_000_000,lookup)

    @Test fun singleSafeIdentifierIsPreservedExactlyWithoutNormalization() {
        val value = "AbC._:-009"
        assertEquals(ShopSyncOrdinaryHttpFailureMetadata.Header("VALID",value),identifier(listOf(value)))
        val maximum = "a".repeat(128)
        assertEquals(maximum,identifier(listOf(maximum)).value)
    }
    @Test fun absentAndDuplicateIdentifiersExposeOnlyTheirState() {
        assertEquals("ABSENT",identifier(null).state)
        assertEquals("ABSENT",identifier(emptyList()).state)
        for (values in listOf(listOf("first","second"),listOf("same","same"))) {
            val actual = identifier(values)
            assertEquals("DUPLICATE",actual.state); assertNull(actual.value)
        }
    }
    @Test fun whitespaceUnsafeUnicodeNewlineAndOversizeIdentifiersAreNotTrimmedOrTruncated() {
        listOf("", " safe", "safe ", "a b", "safe\tvalue", "first\nsecond", "first\rsecond",
            "caf\u00e9", "\ud83d\udd12", "a/b", "a=b", "a;b", "a,b", "a".repeat(129)).forEach { value ->
            val actual = identifier(listOf(value))
            assertEquals("INVALID",actual.state); assertNull(actual.value)
        }
    }
    @Test fun onlyExactFiveCharacterPostgrestSqlStateIsReturned() {
        assertEquals(ShopSyncOrdinaryHttpFailureMetadata.Header("VALID","57014"),proxy(listOf("PostgREST; error=57014")))
        assertEquals("P0001",proxy(listOf("PostgREST ; error = \"P0001\" ")).value)
        assertEquals("23505",proxy(listOf("PostgREST;error=23505")).value)
    }
    @Test fun absentOrDuplicateProxyStatusNeverReturnsAValue() {
        assertEquals("ABSENT",proxy(null).state)
        assertEquals("ABSENT",proxy(emptyList()).state)
        val actual = proxy(listOf("PostgREST; error=57014","PostgREST; error=57014"))
        assertEquals("DUPLICATE",actual.state); assertNull(actual.value)
    }
    @Test fun proxyServerAmbiguityExtraParametersAndNonSqlstateAreRejected() {
        listOf("Other; error=57014", "PostgREST; error=57014, Other; error=57014",
            "PostgREST; error=57014; error=23505", "PostgREST; error=57014; details=private-marker",
            "PostgREST; error=PGRST001", "PostgREST; error=abcde", "PostgREST; error=5701",
            "PostgREST; error=570140", "PostgREST; error=\"57014", "PostgREST", "").forEach { value ->
            val actual = proxy(listOf(value))
            assertEquals("INVALID",actual.state); assertNull(actual.value)
        }
    }
    @Test fun proxyControlUnicodeAndOversizeInputsNeverLeakTheirContents() {
        val base = "PostgREST; error=57014"
        assertEquals("57014",proxy(listOf(base+" ".repeat(256-base.length))).value)
        assertEquals("INVALID",proxy(listOf(base+" ".repeat(257-base.length))).state)
        listOf("PostgREST; error=57014\r\nprivate-marker", "PostgREST; error=57014\n",
            "PostgREST; error=57014\t", "PostgREST; error=57014\u00e9", "x".repeat(257)).forEach { value ->
            val actual = proxy(listOf(value))
            assertEquals("INVALID",actual.state); assertNull(actual.value)
        }
    }
    @Test fun allowedHeaderLookupNeverReadsUnlistedSensitiveHeaders() {
        val lookedUp = mutableListOf<String>()
        val output = message { key ->
            lookedUp.add(key)
            when(key) {
                "request_id" -> listOf("AbC-1")
                "sb-request-id" -> listOf("req_2")
                "cf-ray" -> listOf("ray:3")
                "Proxy-Status" -> listOf("PostgREST; error=57014")
                else -> throw AssertionError("Unlisted header lookup")
            }
        }
        assertEquals(listOf("request_id","sb-request-id","cf-ray","Proxy-Status"),lookedUp)
        assertTrue(output.contains("request_id=AbC-1"))
        assertTrue(output.contains("sb_request_id=req_2"))
        assertTrue(output.contains("cf_ray=ray:3"))
        assertTrue(output.contains("postgrest_sqlstate=57014"))
        assertFalse(output.contains("PostgREST")); assertFalse(output.contains("Proxy-Status"))
    }
    @Test fun rejectedHeadersPublishIndicatorsAndNoRejectedOrRawProxyValue() {
        val output = message { key -> when(key) {
            "request_id" -> listOf("private-marker\nunsafe")
            "sb-request-id" -> listOf("private-first","private-second")
            "cf-ray" -> null
            "Proxy-Status" -> listOf("PostgREST; error=57014; details=private-marker")
            else -> throw AssertionError("Unlisted header lookup")
        } }
        assertTrue(output.contains("request_id_state=INVALID"))
        assertTrue(output.contains("sb_request_id_state=DUPLICATE"))
        assertTrue(output.contains("cf_ray_state=ABSENT"))
        assertTrue(output.contains("proxy_status_state=INVALID"))
        assertFalse(output.contains("private")); assertFalse(output.contains("57014"))
        assertFalse(output.contains("\n")); assertFalse(output.contains("\r"))
    }
    @Test fun metadataIncludesOnlyStaticRpcStatusPidAndTimingFields() {
        val output = message()
        assertEquals("kind=ordinary_shop_sync_http_failure configured_project=TEST rpc=$checkpoint status=500 pid=19757 " +
            "wall_start_ms=1000 wall_headers_ms=2000 invocation_to_headers_ns=1000000 " +
            "request_id_state=ABSENT sb_request_id_state=ABSENT cf_ray_state=ABSENT proxy_status_state=ABSENT",output)
    }
    @Test fun policyAdmitsOnlyNormalDebugTestProjectAndExactlyThreeRpcs() {
        for(function in listOf(checkpoint,page,marker)) {
            assertTrue(ShopSyncOrdinaryHttpFailureMetadata.enabled(function,true,false,testProject))
            assertFalse(ShopSyncOrdinaryHttpFailureMetadata.enabled(function,false,false,testProject))
            assertFalse(ShopSyncOrdinaryHttpFailureMetadata.enabled(function,true,true,testProject))
            assertFalse(ShopSyncOrdinaryHttpFailureMetadata.enabled(function,true,false,"0".repeat(64)))
        }
        for(function in listOf("shop_sync_event_page_v1","shop_sync_rows_by_ids_v1","checkpoint",checkpoint+"\n")) {
            assertFalse(ShopSyncOrdinaryHttpFailureMetadata.enabled(function,true,false,testProject))
        }
    }
    @Test fun responseFormatterCannotEmitForSuccessOrUnlistedDynamicRpc() {
        var rejected = false
        try { message(200) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        rejected = false
        try { ShopSyncOrdinaryHttpFailureMetadata.message("private-dynamic-rpc",500,19757,1,2,3) { null } }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }
}
