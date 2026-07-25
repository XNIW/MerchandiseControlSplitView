package com.example.merchandisecontrolsplitview.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProductImageCaptureFileTrackerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `release deletes a completed or failed capture file`() {
        val file = captureFile("completed.jpg")
        val tracker = ProductImageCaptureFileTracker()
        tracker.track(file)

        tracker.release(file)

        assertFalse(file.exists())
    }

    @Test
    fun `cleanup deletes every pending capture when editor closes`() {
        val first = captureFile("first.jpg")
        val second = captureFile("second.jpg")
        val tracker = ProductImageCaptureFileTracker()
        tracker.track(first)
        tracker.track(second)

        tracker.cleanup()

        assertFalse(first.exists())
        assertFalse(second.exists())
    }

    private fun captureFile(name: String): File =
        temporaryFolder.newFile(name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
}
