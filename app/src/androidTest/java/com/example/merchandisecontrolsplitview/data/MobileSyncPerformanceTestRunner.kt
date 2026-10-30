package com.example.merchandisecontrolsplitview.data

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Test-only entry point: target app auth, sessions and coordinator wiring never start. */
class MobileSyncPerformanceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
