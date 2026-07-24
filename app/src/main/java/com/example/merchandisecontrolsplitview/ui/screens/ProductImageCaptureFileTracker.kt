package com.example.merchandisecontrolsplitview.ui.screens

import java.io.File

internal class ProductImageCaptureFileTracker {
    private val files = mutableSetOf<File>()

    fun track(file: File) {
        files += file
    }

    fun release(file: File?) {
        if (file == null) return
        file.delete()
        files -= file
    }

    fun cleanup() {
        files.toList().forEach(::release)
    }
}
