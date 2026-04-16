package com.example.merchandisecontrolsplitview

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MerchandiseControlApplicationTest {

    @Test
    fun `manifest wires MerchandiseControlApplication with singleton repository owner`() {
        val application = RuntimeEnvironment.getApplication()
        assertTrue(application is MerchandiseControlApplication)

        val typedApplication = application as MerchandiseControlApplication
        assertSame(typedApplication.repository, typedApplication.repository)
        assertSame(
            typedApplication.realtimeRefreshCoordinator,
            typedApplication.realtimeRefreshCoordinator
        )
        assertSame(
            typedApplication.realtimeSessionSubscriber,
            typedApplication.realtimeSessionSubscriber
        )
        assertTrue(typedApplication.realtimeRefreshCoordinator.isForeground)
    }
}
