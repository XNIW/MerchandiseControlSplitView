package com.example.merchandisecontrolsplitview.pos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope

/** Supplied by the existing app auth/device owner; no catalog readiness or canWrite input. */
internal data class PosFeatureEnvironment(
    val scope: PosScope?,
    val online: Boolean,
    val foreground: Boolean,
    val visible: Boolean
)

internal data class PosFeatureBindings(
    val transport: PosReadTransport,
    val isScopeCurrent: (PosScope) -> Boolean,
    val refreshExistingAuthOnce: suspend (PosScope) -> Boolean = { false }
)

/** Native route-scoped lifecycle owner. Host integration remains separate from Application/bootstrap. */
internal class PosSalesViewModel(private val bindings: PosFeatureBindings) : ViewModel() {
    val coordinator = PosSalesCoordinator(viewModelScope, bindings.transport,
        bindings.isScopeCurrent, refreshExistingAuthOnce = bindings.refreshExistingAuthOnce)
    val state = coordinator.state

    fun environment(value: PosFeatureEnvironment) {
        // Internal overview/history/detail/filter navigation survives foreground/auth observations.
        coordinator.environment(value.scope, value.online, value.foreground,
            coordinator.state.value.route, value.visible)
    }

    override fun onCleared() { coordinator.close() }

    companion object {
        fun factory(bindings: PosFeatureBindings): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == PosSalesViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return PosSalesViewModel(bindings) as T
            }
        }
    }
}
