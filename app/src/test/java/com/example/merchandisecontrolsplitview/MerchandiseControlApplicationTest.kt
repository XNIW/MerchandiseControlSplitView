package com.example.merchandisecontrolsplitview

import com.example.merchandisecontrolsplitview.data.AuthState
import org.junit.Assert.assertFalse
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

    @Test
    fun `authManager is singleton and auto-disables without config`() {
        val application = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        // Singleton: stessa istanza a ogni accesso.
        assertSame(application.authManager, application.authManager)
        // In test/CI le chiavi sono vuote: il manager si auto-disabilita.
        assertFalse(application.authManager.isEnabled)
        // Senza config, lo stato deve essere SignedOut (non Checking).
        assertTrue(application.authManager.state.value is AuthState.SignedOut)
    }
}
