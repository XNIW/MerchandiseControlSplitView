package com.example.merchandisecontrolsplitview.data

import android.app.Application
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.example.merchandisecontrolsplitview.LocalStoreAvailability
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BootstrapPersistedWorkerGateTest {
    @Test fun persistedWorkerCannotReachDatabaseOrScopeWhilePhysicalOpenIsHeldOrFailed() = runTest {
        for (availability in listOf(LocalStoreAvailability.Checking,
                LocalStoreAvailability.Unavailable("local_store_open_failed"))) {
            val app = mockk<MerchandiseControlApplication>()
            every { app.localStoreAvailability } returns MutableStateFlow(availability)
            every { app.database } answers { error("DB getter before physical Ready") }
            every { app.applicationContext } returns app
            val worker = PriceBackfillWorker(app, mockk<WorkerParameters>(relaxed = true))
            assertEquals(ListenableWorker.Result.retry(), worker.doWork())
            verify(atLeast = 1) { app.localStoreAvailability }
            verify(exactly = 0) { app.database }
            verify(exactly = 0) { app.catalogSyncStateTracker }
        }
    }

    @Test fun persistedWorkerKeepsExistingWriterFlightAndCannotWriteWithReadOnlyCapability() = runTest {
        val app = mockk<MerchandiseControlApplication>()
        every { app.localStoreAvailability } returns MutableStateFlow(LocalStoreAvailability.Ready)
        every { app.catalogSyncStateTracker } returns CatalogSyncStateTracker(
            Task126BusinessDataScopeState.unmanagedAllowed().copy(localWritesAllowed = false))
        every { app.database } answers { error("Read-only flight must reject before DB getter") }
        every { app.applicationContext } returns app
        val worker = PriceBackfillWorker(app, mockk<WorkerParameters>(relaxed = true))
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        verify(exactly = 1) { app.catalogSyncStateTracker }
        verify(exactly = 0) { app.database }
    }
}
