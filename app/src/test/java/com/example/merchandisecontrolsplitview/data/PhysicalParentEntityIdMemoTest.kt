package com.example.merchandisecontrolsplitview.data

import android.app.Application
import android.database.Cursor
import android.database.MatrixCursor
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real private memo/canonicalizer/physical projection; no alternate parser or DB admission cache. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PhysicalParentEntityIdMemoTest {
    private val facade = Class.forName("com.example.merchandisecontrolsplitview.data.ShopSyncRecoveryCoordinatorKt")
    private val memoType = Class.forName("com.example.merchandisecontrolsplitview.data.PhysicalParentEntityIdMemo")

    @Test
    fun successfulExactRawKeysReuseOnlyTheirPureCanonicalResultAndFailuresStayUncached() {
        val memo = newMemo()
        val raw = SUPPLIER.uppercase()
        val first = canonical(memo, String(raw.toCharArray()))
        assertEquals(originalCanonical(raw), first)
        assertSame("Equal fresh raw strings must hit the actual production map", first,
            canonical(memo, String(raw.toCharArray())))
        assertEquals(1, entries(memo).size)
        assertEquals(originalCanonical(" $raw "), canonical(memo, " $raw "))
        assertEquals("Raw normalization variants remain separate exact keys", 2, entries(memo).size)
        for (invalid in listOf("", "not-a-uuid", "10000000-0000-6000-8000-0000000000aa",
            "10000000-0000-4000-7000-0000000000aa", "$SUPPLIER\u0000")) {
            val before = entries(memo).toMap()
            val expected = failure { originalCanonical(invalid) }
            val firstFailure = failure { canonical(memo, invalid) }
            val secondFailure = failure { canonical(memo, invalid) }
            assertEquals(expected.javaClass, firstFailure.javaClass)
            assertEquals(expected.message, firstFailure.message)
            assertEquals(expected.message, secondFailure.message)
            assertNotSame("A failed canonicalization is never retained", firstFailure, secondFailure)
            assertEquals(before, entries(memo))
        }
    }

    @Test
    fun storageIsBoundedAndOversizedSuccessfulRawValuesAndLaterBatchesRemainIndependent() {
        val memo = newMemo()
        for (number in 1..600) {
            val id = "10000000-0000-4000-8000-" + number.toString(16).padStart(12, '0')
            assertEquals(originalCanonical(id), canonical(memo, id))
            assertTrue(entries(memo).size <= 500)
        }
        assertEquals(500, entries(memo).size)
        val anotherBatch = newMemo()
        assertTrue("No completed-page state enters another batch", entries(anotherBatch).isEmpty())
        val oversized = " ".repeat(129) + SUPPLIER
        assertEquals(originalCanonical(oversized), canonical(anotherBatch, oversized))
        assertTrue("Length limits caching only, never accepted values", entries(anotherBatch).isEmpty())
        canonical(anotherBatch, SUPPLIER)
        assertEquals(setOf(SUPPLIER), entries(anotherBatch).keys)
        assertEquals(500, entries(memo).size)
    }

    @Test
    fun cachedAndUncachedPhysicalRowsObserveEveryFreshBodyAndParentValueIncludingFailures() {
        val memo = newMemo()
        val base = baseRow()
        val expected = project(base, null)
        assertEquals("The original physical projection must genuinely succeed", "OK", expected.first())
        assertEquals(expected, project(base, memo))
        assertEquals(setOf(SUPPLIER, CATEGORY), entries(memo).keys)
        val changedParent = base.toMutableList().also { it[10] = OTHER_PARENT }
        val changedBody = base.toMutableList().also { it[3] = "Another product" }
        for (row in listOf(
            base,
            changedParent,
            changedBody,
            base.toMutableList().also { it[10] = " ${SUPPLIER.uppercase()} " },
            base.toMutableList().also { it[11] = null },
            base.toMutableList().also { it[10] = "bad-parent" },
            base.toMutableList().also { it[1] = "" },
            base.toMutableList().also { it[13] = 2L }
        )) {
            assertEquals("The current cursor body and original rejection remain authoritative",
                project(row, null), project(row, memo))
        }
        assertFalse("A changed valid parent must still change the physical proof", expected == project(changedParent, memo))
        assertFalse("A changed product body must still change the physical proof", expected == project(changedBody, memo))
    }

    private fun newMemo(): Any = memoType.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()

    @Suppress("UNCHECKED_CAST")
    private fun entries(memo: Any): Map<String, String> = memoType.getDeclaredField("accepted")
        .also { it.isAccessible = true }.get(memo) as Map<String, String>

    private fun canonical(memo: Any, raw: String): String = invoke {
        memoType.getDeclaredMethod("canonical", String::class.java).also { it.isAccessible = true }.invoke(memo, raw) as String
    }

    private fun originalCanonical(raw: String): String = invoke {
        facade.getDeclaredMethod("canonicalRecoveryEntityUuid", String::class.java)
            .also { it.isAccessible = true }.invoke(null, raw) as String
    }

    private fun project(row: List<Any?>, memo: Any?): List<Any?> = try {
        MatrixCursor(Array(15) { "column$it" }).use { cursor ->
            cursor.addRow(row.toTypedArray())
            check(cursor.moveToFirst())
            val result = if (memo == null) {
                facade.getDeclaredMethod("physicalRow", ShopSyncRowDomain::class.java,
                    Cursor::class.java, java.lang.Boolean.TYPE, Set::class.java)
                    .also { it.isAccessible = true }
                    .invoke(null, ShopSyncRowDomain.PRODUCTS, cursor, true, emptySet<String>())
            } else {
                facade.getDeclaredMethod("physicalRow", ShopSyncRowDomain::class.java,
                    Cursor::class.java, java.lang.Boolean.TYPE, Set::class.java, memoType)
                    .also { it.isAccessible = true }
                    .invoke(null, ShopSyncRowDomain.PRODUCTS, cursor, true, emptySet<String>(), memo)
            }
            val actual = requireNotNull(result)
            listOf("OK") + listOf("remoteId", "payloadDigest", "versionLine", "history",
                "localAckFingerprint", "acknowledgedRevision").map { name ->
                actual.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(actual)
            }
        }
    } catch (error: Throwable) {
        val actual = unwrap(error)
        listOf("ERROR", actual.javaClass.name, actual.message)
    }

    private fun failure(block: () -> Any?): Throwable {
        try { block() } catch (error: Throwable) { return unwrap(error) }
        throw AssertionError("Expected original rejection")
    }

    private fun <T> invoke(block: () -> T): T = try { block() } catch (error: InvocationTargetException) {
        throw unwrap(error)
    }

    private fun unwrap(error: Throwable): Throwable = if (error is InvocationTargetException) error.targetException else error

    private fun baseRow(): List<Any?> = listOf(
        PRODUCT, "BC100", "ITEM100", "Product", null, 2.5, 4.5, 6.0,
        null, null, SUPPLIER, CATEGORY, "2026-10-29T08:00:00.000000Z", 1L, 1L
    )

    companion object {
        private const val PRODUCT = "10000000-0000-4000-8000-0000000000cc"
        private const val SUPPLIER = "10000000-0000-4000-8000-0000000000aa"
        private const val CATEGORY = "10000000-0000-4000-8000-0000000000bb"
        private const val OTHER_PARENT = "10000000-0000-4000-8000-0000000000dd"
    }
}
