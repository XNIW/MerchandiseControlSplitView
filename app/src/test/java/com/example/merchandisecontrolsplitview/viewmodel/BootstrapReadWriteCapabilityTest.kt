package com.example.merchandisecontrolsplitview.viewmodel

import android.app.Application
import com.example.merchandisecontrolsplitview.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BootstrapReadWriteCapabilityTest {
    @Test fun readOnlySnapshotSubscribesToReadFlowButNeverExecutesExistingWriterCallback() = runTest {
        val snapshot = LocalBusinessReadSnapshot(null,
            Task126BusinessDataScopeState.unmanagedAllowed().copy(localWritesAllowed = false),
            Task126LocalPresentationStamp(1), true)
        val state = MutableStateFlow(snapshot)
        val gate = LocalBusinessReadGate(state)
        var reads = 0
        var writes = 0
        val seen = mutableListOf<String>()
        backgroundScope.launch { gate.observe("") { reads++; flowOf("qualified local row") }.collect { seen += it } }
        runCurrent()
        assertEquals(1, reads)
        assertEquals(listOf("qualified local row"), seen)
        assertTrue(gate.isCurrent(snapshot))
        assertFalse(gate.isWriteCurrent(snapshot))
        assertNull(gate.runIfCurrent(snapshot) { writes++; "must not execute" })
        assertEquals(0, writes)
    }

    @Test fun writerCallbackKeepsItsOriginalGenerationAndRequiresFreshWritableAdmission() = runTest {
        val initial = LocalBusinessReadSnapshot(null, Task126BusinessDataScopeState.unmanagedAllowed(),
            Task126LocalPresentationStamp(1), true)
        val state = MutableStateFlow(initial)
        val gate = LocalBusinessReadGate(state)
        assertEquals("saved", gate.runIfCurrent(initial) { "saved" })
        state.value = initial.copy(state = initial.state!!.copy(localWritesAllowed = false))
        var calls = 0
        assertNull(gate.runIfCurrent(initial) { calls++; "stale" })
        assertNull(gate.runIfCurrent(state.value) { calls++; "read only" })
        assertEquals(0, calls)
    }
}
