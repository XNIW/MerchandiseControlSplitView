package com.example.merchandisecontrolsplitview.ui

import android.os.SystemClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

/** Optional in-process observation for the native T20 fixture. No production observer is installed.
 * The clock and callback run only when installed; drawing, state and admission are unchanged.
 * Consumers must only capture timestamps here and perform I/O/readback outside the callback.
 */
internal object MobileSyncInteractionTestHooks {
    enum class Event { FILTER_INPUT, PRODUCT_DRAW, EDITOR_OPEN, EDITOR_DRAW, SAVE_CLICK,
        SAVE_DURABLE_RETURN, ROOT_TAB_CLICK, ROOT_TAB_DRAW, HISTORY_DRAW, PRICE_HISTORY_DRAW, DELETE_DATASET_DRAW }

    @Volatile var observer: ((Event, Long, String?, Long) -> Unit)? = null

    @Volatile var observationError: String? = null

    fun emit(event: Event, productId: Long = 0L, detail: String? = null) {
        val installed = observer ?: return
        try {
            installed(event, productId, detail, SystemClock.elapsedRealtimeNanos())
        } catch (error: Exception) {
            // An observation failure cannot turn a committed Save into a failed operation.
            observationError = error.javaClass.simpleName
        }
    }
}

/** An actual draw boundary, never a composition/side-effect approximation of rendered content. */
internal fun Modifier.observeMobileSyncDraw(
    event: MobileSyncInteractionTestHooks.Event,
    productId: Long = 0L,
    detail: String? = null,
    enabled: Boolean = true
): Modifier = if (!enabled || MobileSyncInteractionTestHooks.observer == null) this else drawWithContent {
    drawContent()
    MobileSyncInteractionTestHooks.emit(event, productId, detail)
}
