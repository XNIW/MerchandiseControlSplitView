package com.example.merchandisecontrolsplitview.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportResourcePolicyTest {

    @Test
    fun `readBytes accepts a source exactly at the configured limit`() {
        val input = byteArrayOf(1, 2, 3, 4)

        val result = ImportResourcePolicy.readBytes(
            input = ByteArrayInputStream(input),
            maxBytes = input.size.toLong()
        )

        assertArrayEquals(input, result)
    }

    @Test
    fun `readBytes rejects a source beyond the configured limit`() {
        val error = assertThrows(ImportResourceLimitExceededException::class.java) {
            ImportResourcePolicy.readBytes(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                maxBytes = 3L
            )
        }

        assertEquals("source", error.resource)
        assertEquals(3L, error.limitBytes)
    }

    @Test
    fun `copy does not publish bytes when a single read exceeds the limit`() {
        val output = ByteArrayOutputStream()

        assertThrows(ImportResourceLimitExceededException::class.java) {
            ImportResourcePolicy.copy(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                output = output,
                maxBytes = 3L
            )
        }

        assertEquals(0, output.size())
    }

    @Test
    fun `archive budget rejects excessive entry count`() {
        val budget = ImportResourcePolicy.ArchiveBudget(
            maxEntries = 1,
            maxEntryBytes = 8L,
            maxExpandedBytes = 16L
        )
        budget.readEntry(ByteArrayInputStream(byteArrayOf(1)))

        val error = assertThrows(ImportResourceLimitExceededException::class.java) {
            budget.readEntry(ByteArrayInputStream(byteArrayOf(2)))
        }

        assertEquals("archive-entry-count", error.resource)
    }

    @Test
    fun `archive budget rejects forged metadata through cumulative runtime bytes`() {
        val budget = ImportResourcePolicy.ArchiveBudget(
            maxEntries = 3,
            maxEntryBytes = 8L,
            maxExpandedBytes = 5L
        )
        budget.readEntry(
            input = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            declaredSizeBytes = 1L
        )

        val error = assertThrows(ImportResourceLimitExceededException::class.java) {
            budget.readEntry(
                input = ByteArrayInputStream(byteArrayOf(4, 5, 6)),
                declaredSizeBytes = 1L
            )
        }

        assertEquals("archive-expanded-runtime", error.resource)
        assertEquals(5L, error.limitBytes)
    }

    @Test
    fun `archive budget rejects declared entry before reading it`() {
        val budget = ImportResourcePolicy.ArchiveBudget(
            maxEntries = 1,
            maxEntryBytes = 2L,
            maxExpandedBytes = 4L
        )

        val error = assertThrows(ImportResourceLimitExceededException::class.java) {
            budget.readEntry(
                input = ByteArrayInputStream(byteArrayOf()),
                declaredSizeBytes = 3L
            )
        }

        assertEquals("archive-entry", error.resource)
        assertEquals(2L, error.limitBytes)
    }
}
