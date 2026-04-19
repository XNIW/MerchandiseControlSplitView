package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryRemoteFetchSupportTest {

    @Test
    fun `fetchAllPagesByIndexedRange empty first page returns empty`() = runTest {
        val r = fetchAllPagesByIndexedRange<String>(
            pageSize = 10L,
            maxPageIterations = 100,
            tableLabel = "t"
        ) { _, _ -> emptyList() }
        assertTrue(r.isEmpty())
    }

    @Test
    fun `fetchAllPagesByIndexedRange single short page`() = runTest {
        val r = fetchAllPagesByIndexedRange(
            pageSize = 10L,
            maxPageIterations = 100,
            tableLabel = "t"
        ) { _: Long, _: Long -> listOf("a", "b") }
        assertEquals(listOf("a", "b"), r)
    }

    @Test
    fun `fetchAllPagesByIndexedRange full page then short tail`() = runTest {
        var calls = 0
        val r = fetchAllPagesByIndexedRange(
            pageSize = 3L,
            maxPageIterations = 100,
            tableLabel = "t"
        ) { from: Long, to: Long ->
            calls++
            when (calls) {
                1 -> {
                    assertEquals(0L, from)
                    assertEquals(2L, to)
                    listOf("a", "b", "c")
                }
                2 -> {
                    assertEquals(3L, from)
                    assertEquals(5L, to)
                    listOf("d")
                }
                else -> error("unexpected call $calls")
            }
        }
        assertEquals(listOf("a", "b", "c", "d"), r)
        assertEquals(2, calls)
    }

    @Test
    fun `fetchAllPagesByIndexedRange full page then empty ends`() = runTest {
        var calls = 0
        val r = fetchAllPagesByIndexedRange(
            pageSize = 2L,
            maxPageIterations = 100,
            tableLabel = "t"
        ) { _: Long, _: Long ->
            calls++
            when (calls) {
                1 -> listOf("x", "y")
                2 -> emptyList()
                else -> error("unexpected")
            }
        }
        assertEquals(listOf("x", "y"), r)
        assertEquals(2, calls)
    }

    @Test(expected = IllegalStateException::class)
    fun `fetchAllPagesByIndexedRange fail-safe max iterations`() = runTest {
        fetchAllPagesByIndexedRange(
            pageSize = 2L,
            maxPageIterations = 3,
            tableLabel = "t"
        ) { _: Long, _: Long -> listOf("a", "b") }
    }

    @Test
    fun `fetchAllPagesByIndexedRange propagates intermediate page failure without later calls`() = runTest {
        var calls = 0
        val error = runCatching {
            fetchAllPagesByIndexedRange(
                pageSize = 2L,
                maxPageIterations = 100,
                tableLabel = "t"
            ) { _: Long, _: Long ->
                calls++
                when (calls) {
                    1 -> listOf("a", "b")
                    2 -> error("network")
                    else -> error("unexpected call $calls")
                }
            }
        }.exceptionOrNull()

        assertEquals("network", error?.message)
        assertEquals(2, calls)
    }
}
