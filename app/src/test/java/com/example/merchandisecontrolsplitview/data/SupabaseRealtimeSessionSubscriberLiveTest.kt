package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.room.Room
import com.example.merchandisecontrolsplitview.MerchandiseControlApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifica live minima del percorso task 010:
 * `postgres_changes -> SupabaseRealtimeSessionSubscriber -> RealtimeRefreshCoordinator
 * -> DefaultInventoryRepository.applyRemoteSessionPayloadBatch -> Room`.
 *
 * Il test e' volutamente opt-in:
 * - richiede `SUPABASE_URL` e `SUPABASE_PUBLISHABLE_KEY` disponibili in BuildConfig;
 * - richiede `SUPABASE_TEST_REMOTE_ID` a runtime;
 * - l'insert remoto viene eseguito esternamente (SQL/MCP) dopo il messaggio READY.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SupabaseRealtimeSessionSubscriberLiveTest {

    companion object {
        private const val TEST_REMOTE_ID_ENV = "SUPABASE_TEST_REMOTE_ID"
        private const val EXPECTED_TIMESTAMP = "2026-04-16 21:30:00"
        private const val EXPECTED_SUPPLIER = "Realtime Supplier"
        private const val EXPECTED_CATEGORY = "Realtime Category"
        private val EXPECTED_DATA = listOf(
            listOf("barcode", "purchasePrice", "quantity"),
            listOf("123456789", "18.50", "2")
        )
    }

    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultInventoryRepository

    @Before
    fun setup() {
        val application = RuntimeEnvironment.getApplication()
        if (application is MerchandiseControlApplication) {
            application.realtimeSessionSubscriber.shutdown()
            application.realtimeRefreshCoordinator.shutdown()
        }

        val context: Context = application
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultInventoryRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `live realtime insert is materialized into Room`() = runBlocking {
        val application = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        val isEnabled = application.supabaseClient != null
        val remoteId = System.getenv(TEST_REMOTE_ID_ENV)?.trim().orEmpty()

        assumeTrue("SUPABASE realtime config assente", isEnabled)
        assumeTrue("SUPABASE_TEST_REMOTE_ID mancante", remoteId.isNotBlank())

        val coordinator = RealtimeRefreshCoordinator(
            repository = repository,
            debounceMs = 50L
        )
        val subscriber = SupabaseRealtimeSessionSubscriber(
            client = application.supabaseClient,
            coordinator = coordinator
        )

        try {
            subscriber.start()
            assertTrue(
                "Subscription realtime non attiva",
                subscriber.awaitSubscribed(timeoutMs = 30_000L)
            )

            println("SUPABASE_REALTIME_READY:$remoteId")

            val remoteRef = awaitRemoteRef(remoteId, timeoutMs = 30_000L)
            val entry = db.historyEntryDao().getByUid(remoteRef.historyEntryUid)
            assertNotNull("HistoryEntry materializzata mancante", entry)

            entry!!
            assertEquals(remoteId, remoteRef.remoteId)
            assertEquals(EXPECTED_TIMESTAMP, entry.timestamp)
            assertEquals(EXPECTED_SUPPLIER, entry.supplier)
            assertEquals(EXPECTED_CATEGORY, entry.category)
            assertEquals(false, entry.isManualEntry)
            assertEquals(EXPECTED_DATA, entry.data)
        } finally {
            subscriber.shutdown()
            coordinator.shutdown()
        }
    }

    private suspend fun awaitRemoteRef(
        remoteId: String,
        timeoutMs: Long
    ): HistoryEntryRemoteRef {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            db.historyEntryRemoteRefDao().getByRemoteId(remoteId)?.let { return it }
            delay(100L)
        }
        throw AssertionError("Timeout in attesa del remoteId=$remoteId in Room")
    }
}
