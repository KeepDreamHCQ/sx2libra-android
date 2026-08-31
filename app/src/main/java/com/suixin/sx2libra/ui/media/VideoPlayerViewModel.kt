package com.suixin.sx2libra.ui.media

import com.suixin.sx2libra.model.PlaybackSnapshot
import com.suixin.sx2libra.model.VideoAspect
import com.suixin.sx2libra.model.VideoRequest
import java.util.concurrent.CopyOnWriteArrayList

val SUPPORTED_PLAYBACK_SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

data class VideoPlayerUiState(
    val request: VideoRequest,
    val snapshot: PlaybackSnapshot,
    val castState: CastState = CastState.Idle,
    val castControlsLocked: Boolean = false,
    val error: CastFailure? = null
)

/** Player state only; the GSY instance remains owned by VideoPlayerActivity. */
class VideoPlayerViewModel(
    request: VideoRequest,
    private val castController: VideoCastController = UnavailableVideoCastController()
) {
    private val observers = CopyOnWriteArrayList<(VideoPlayerUiState) -> Unit>()
    private var castSession: VideoCastSession? = null
    @Volatile
    private var active = true
    @Volatile
    private var state = VideoPlayerUiState(
        request = request,
        snapshot = PlaybackSnapshot(
            positionMillis = 0L,
            durationMillis = 0L,
            isPlaying = false,
            isBuffering = false,
            speed = 1.0f,
            aspect = VideoAspect.DEFAULT
        )
    )

    fun currentState(): VideoPlayerUiState = state

    fun observe(observer: (VideoPlayerUiState) -> Unit): AutoCloseable {
        observers += observer
        observer(state)
        return AutoCloseable { observers.remove(observer) }
    }

    fun onPlaybackSnapshot(snapshot: PlaybackSnapshot) {
        if (!active) return
        update(state.copy(snapshot = snapshot))
    }

    fun setSpeed(speed: Float): Boolean {
        if (!active || state.castControlsLocked) return false
        val selected = SUPPORTED_PLAYBACK_SPEEDS.firstOrNull { kotlin.math.abs(it - speed) < 0.001f }
            ?: return false
        update(state.copy(snapshot = state.snapshot.copy(speed = selected)))
        return true
    }

    fun setAspect(aspect: VideoAspect): Boolean {
        if (!active || state.castControlsLocked) return false
        update(state.copy(snapshot = state.snapshot.copy(aspect = aspect)))
        return true
    }

    fun startDiscovery() {
        if (!active || state.castState is CastState.Connected) return
        update(state.copy(castState = CastState.Discovering, error = null))
        castController.discover(::onCastState)
    }

    fun stopDiscovery() {
        if (!active) return
        castController.stopDiscovery()
        if (state.castState is CastState.Discovering || state.castState is CastState.Devices) {
            update(state.copy(castState = CastState.Idle))
        }
    }

    fun connect(device: CastDevice): Boolean {
        if (!active) return false
        val snapshot = state.snapshot
        val request = CastSessionRequest(
            mediaUrl = state.request.url,
            mimeType = state.request.mimeType,
            title = state.request.title,
            positionMillis = snapshot.positionMillis
        )
        update(state.copy(castState = CastState.Connecting(device), error = null))
        val session = castController.connect(device, request, ::onCastState)
        castSession = session
        if (session == null) {
            onCastState(CastState.Failed(CastFailure.CONNECTION_FAILED))
            return false
        }
        return true
    }

    fun playCast() {
        if (state.castControlsLocked) castSession?.play()
    }

    fun pauseCast() {
        if (state.castControlsLocked) castSession?.pause()
    }

    fun seekCast(positionMillis: Long) {
        if (state.castControlsLocked && positionMillis >= 0L) castSession?.seekTo(positionMillis)
    }

    fun disconnectCast() {
        val session = castSession
        castSession = null
        session?.disconnect()
        if (active) update(state.copy(castState = CastState.Idle, castControlsLocked = false))
    }

    fun clear() {
        active = false
        castController.stopDiscovery()
        castSession?.disconnect()
        castSession = null
        observers.clear()
    }

    private fun onCastState(castState: CastState) {
        if (!active) return
        val connected = castState is CastState.Connected
        val nextSnapshot = if (connected) state.snapshot.copy(speed = 1.0f) else state.snapshot
        update(
            state.copy(
                castState = castState,
                castControlsLocked = connected,
                snapshot = nextSnapshot,
                error = (castState as? CastState.Failed)?.reason
            )
        )
    }

    private fun update(next: VideoPlayerUiState) {
        state = next
        observers.forEach { observer ->
            try {
                observer(next)
            } catch (_: RuntimeException) {
                // One stale view must not stop state delivery to other views.
            }
        }
    }
}
