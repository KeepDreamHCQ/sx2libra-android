package com.suixin.sx2libra.ui.media

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import com.suixin.sx2libra.model.MediaUrlPolicy
import com.suixin.sx2libra.model.VideoAspect
import com.suixin.sx2libra.model.VideoRequest

/**
 * Narrow player surface owned by the Activity. A real GSY 13.x adapter can be
 * supplied by the host build; the compile-safe default uses framework
 * VideoView for local playback only and disables capabilities it cannot
 * honestly provide (speed and VTT preview).
 */
interface GsyPlayerBackend {
    val supportsSpeed: Boolean
    val supportsPreviewTrack: Boolean
    fun attach(parent: FrameLayout)
    fun setVideo(request: VideoRequest)
    fun start()
    fun pause()
    fun seekTo(positionMillis: Long)
    fun setSpeed(speed: Float): Boolean
    fun setAspect(aspect: VideoAspect): Boolean
    fun setPreviewVttUrl(url: String): Boolean
    fun previewFrame(positionMillis: Long): GsyPreviewFrame? = null
    fun currentPosition(): Long
    fun duration(): Long
    fun isPlaying(): Boolean
    fun release()
}

class LibraGSYVideoPlayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val gsyBackend: GsyPlayerBackend? = null
) : FrameLayout(context, attrs) {
    private val fallbackVideo = VideoView(context)
    private var progressListener: ((Long, Long, Boolean) -> Unit)? = null

    init {
        setBackgroundColor(0xff000000.toInt())
        if (gsyBackend == null) {
            addView(
                fallbackVideo,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            fallbackVideo.setMediaController(MediaController(context).also { controller ->
                controller.setAnchorView(this@LibraGSYVideoPlayer)
            })
            fallbackVideo.setOnPreparedListener { dispatchProgress() }
            fallbackVideo.setOnCompletionListener { dispatchProgress() }
        } else {
            gsyBackend.attach(this)
        }
    }

    fun setVideo(request: VideoRequest): Boolean {
        if (!MediaUrlPolicy.isAllowedVideoUrl(request.url, request.mimeType)) return false
        if (gsyBackend != null) {
            gsyBackend.setVideo(request)
        } else {
            fallbackVideo.setVideoURI(Uri.parse(request.url))
        }
        request.previewVttUrl?.let { setPreviewVttUrl(it) }
        return true
    }

    fun startPlayback() {
        if (gsyBackend != null) gsyBackend.start() else fallbackVideo.start()
        dispatchProgress()
    }

    fun pausePlayback() {
        if (gsyBackend != null) gsyBackend.pause() else fallbackVideo.pause()
        dispatchProgress()
    }

    fun seekTo(positionMillis: Long) {
        if (positionMillis < 0L) return
        if (gsyBackend != null) gsyBackend.seekTo(positionMillis) else fallbackVideo.seekTo(positionMillis.toInt())
        dispatchProgress()
    }

    /** Returns false when the selected backend does not implement speed. */
    fun setPlaybackSpeed(speed: Float): Boolean {
        if (speed !in SUPPORTED_PLAYBACK_SPEEDS) return false
        if (gsyBackend != null) return gsyBackend.setSpeed(speed)
        return false
    }

    /** Returns false when the selected backend cannot apply this aspect. */
    fun setAspect(aspect: VideoAspect): Boolean {
        if (gsyBackend != null) return gsyBackend.setAspect(aspect)
        // VideoView cannot implement crop/stretch semantics reliably. Do not
        // claim success; the UI can keep the control disabled in fallback mode.
        return false
    }

    /** VTT is delegated only to a backend that explicitly supports it. */
    fun setPreviewVttUrl(url: String): Boolean {
        if (!MediaUrlPolicy.isAllowedVttUrl(url)) return false
        return gsyBackend?.takeIf { it.supportsPreviewTrack }?.setPreviewVttUrl(url) == true
    }

    fun previewFrame(positionMillis: Long): GsyPreviewFrame? =
        gsyBackend?.previewFrame(positionMillis)

    fun currentPosition(): Long = gsyBackend?.currentPosition() ?: fallbackVideo.currentPosition.toLong()

    fun duration(): Long = gsyBackend?.duration() ?: fallbackVideo.duration.toLong().coerceAtLeast(0L)

    fun isPlaying(): Boolean = gsyBackend?.isPlaying() ?: fallbackVideo.isPlaying

    fun setProgressListener(listener: ((position: Long, duration: Long, isPlaying: Boolean) -> Unit)?) {
        progressListener = listener
        dispatchProgress()
    }

    fun release() {
        progressListener = null
        if (gsyBackend != null) gsyBackend.release() else fallbackVideo.stopPlayback()
    }

    private fun dispatchProgress() {
        progressListener?.invoke(currentPosition(), duration(), isPlaying())
        if (isPlaying()) {
            postDelayed({ dispatchProgress() }, PROGRESS_POLL_MILLIS)
        }
    }

    companion object {
        private const val PROGRESS_POLL_MILLIS = 250L
    }
}
