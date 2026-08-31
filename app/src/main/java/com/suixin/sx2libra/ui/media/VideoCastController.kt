package com.suixin.sx2libra.ui.media

/** Provider-neutral boundary for a real DLNA/UPnP implementation. */
data class CastDevice(val id: String, val name: String)

data class CastSessionRequest(
    val mediaUrl: String,
    val mimeType: String,
    val title: String?,
    val positionMillis: Long
)

enum class CastFailure {
    PROVIDER_UNAVAILABLE,
    DISCOVERY_FAILED,
    CONNECTION_FAILED,
    DEVICE_OFFLINE,
    UNSUPPORTED_MEDIA,
    NETWORK_ERROR
}

sealed class CastState {
    data object Idle : CastState()
    data object Discovering : CastState()
    data class Devices(val devices: List<CastDevice>) : CastState()
    data class Connecting(val device: CastDevice) : CastState()
    data class Connected(val device: CastDevice, val positionMillis: Long) : CastState()
    data class Failed(val reason: CastFailure) : CastState()
}

interface VideoCastSession {
    fun play()
    fun pause()
    fun seekTo(positionMillis: Long)
    fun stop()
    fun disconnect()
}

interface VideoCastController {
    fun discover(callback: (CastState) -> Unit)
    fun connect(
        device: CastDevice,
        request: CastSessionRequest,
        callback: (CastState) -> Unit
    ): VideoCastSession?

    fun stopDiscovery()
}

/**
 * Explicit compile-safe fallback used until a published cast artifact is
 * selected. It reports unavailability to the UI and never claims that a
 * device was discovered or that media was played.
 */
class UnavailableVideoCastController : VideoCastController {
    override fun discover(callback: (CastState) -> Unit) {
        callback(CastState.Failed(CastFailure.PROVIDER_UNAVAILABLE))
    }

    override fun connect(
        device: CastDevice,
        request: CastSessionRequest,
        callback: (CastState) -> Unit
    ): VideoCastSession? {
        callback(CastState.Failed(CastFailure.PROVIDER_UNAVAILABLE))
        return null
    }

    override fun stopDiscovery() = Unit
}

