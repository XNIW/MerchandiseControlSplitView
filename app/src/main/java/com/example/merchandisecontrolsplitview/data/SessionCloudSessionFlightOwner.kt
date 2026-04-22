package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SessionCloudFlightOwner {
    Refresh,
    AutoPush
}

class SessionCloudSessionFlightOwner(
    private val logger: (String) -> Unit = {}
) {
    private val mutex = Mutex()

    suspend fun <T> withSessionFlight(
        owner: SessionCloudFlightOwner,
        block: suspend () -> T
    ): T {
        val ownerLabel = owner.logLabel
        val hadWaiter = mutex.isLocked
        if (hadWaiter) {
            logger("cycle=session_flight outcome=wait owner=$ownerLabel reason=session_flight_wait")
        }
        return mutex.withLock {
            logger("cycle=session_flight outcome=acquired owner=$ownerLabel waiters=${if (hadWaiter) 1 else 0}")
            try {
                block()
            } finally {
                logger("cycle=session_flight outcome=released owner=$ownerLabel pendingRetry=false")
            }
        }
    }
}

internal val SessionCloudFlightOwner.logLabel: String
    get() = when (this) {
        SessionCloudFlightOwner.Refresh -> "refresh"
        SessionCloudFlightOwner.AutoPush -> "auto_push"
    }
