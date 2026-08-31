package com.suixin.sx2libra.ui.media

import com.suixin.sx2libra.data.repository.MediaRepository
import com.suixin.sx2libra.model.MediaPreviewRequest
import com.suixin.sx2libra.model.MediaSaveResult
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future

data class MediaPreviewUiState(
    val request: MediaPreviewRequest,
    val currentIndex: Int,
    val isSaving: Boolean = false,
    val saveResult: MediaSaveResult? = null,
    val retryNonce: Long = 0L,
    val pendingSaveActionId: String? = null
)

/**
 * Pure screen state holder. It never references a PhotoView, Activity,
 * Bitmap, WebView or reply channel.
 */
class MediaPreviewViewModel(
    private val repository: MediaRepository,
    request: MediaPreviewRequest
) {
    private val observers = CopyOnWriteArrayList<(MediaPreviewUiState) -> Unit>()
    private var saveFuture: Future<*>? = null
    @Volatile
    private var state: MediaPreviewUiState = MediaPreviewUiState(request, request.initialIndex)

    fun currentState(): MediaPreviewUiState = state

    fun observe(observer: (MediaPreviewUiState) -> Unit): AutoCloseable {
        observers += observer
        observer(state)
        return AutoCloseable { observers.remove(observer) }
    }

    fun select(index: Int) {
        if (index !in state.request.urls.indices) return
        update(state.copy(currentIndex = index, saveResult = null))
    }

    fun requestRetry() {
        update(state.copy(retryNonce = state.retryNonce + 1L))
    }

    /** Returns a one-shot ID for a View to show its save confirmation UI. */
    fun requestSaveAction(): String? {
        if (state.isSaving) return null
        val actionId = UUID.randomUUID().toString()
        update(state.copy(pendingSaveActionId = actionId))
        return actionId
    }

    fun onSaveActionHandled(actionId: String) {
        if (state.pendingSaveActionId == actionId) update(state.copy(pendingSaveActionId = null))
    }

    fun saveCurrentImage(suggestedName: String? = null) {
        if (state.isSaving) return
        val url = state.request.urls[state.currentIndex]
        update(state.copy(isSaving = true, saveResult = null, pendingSaveActionId = null))
        saveFuture = repository.saveImageAsync(url, suggestedName) { result ->
            update(state.copy(isSaving = false, saveResult = result))
        }
    }

    fun clear() {
        saveFuture?.cancel(true)
        saveFuture = null
        observers.clear()
    }

    private fun update(newState: MediaPreviewUiState) {
        state = newState
        observers.forEach { observer ->
            try {
                observer(newState)
            } catch (_: RuntimeException) {
                // A destroyed View must not break state delivery to siblings.
            }
        }
    }
}

