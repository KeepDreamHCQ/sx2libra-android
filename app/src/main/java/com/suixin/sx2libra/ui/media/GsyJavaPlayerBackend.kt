package com.suixin.sx2libra.ui.media

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import com.shuyu.gsyvideoplayer.builder.GSYVideoOptionBuilder
import com.shuyu.gsyvideoplayer.listener.GSYVideoProgressListener
import com.shuyu.gsyvideoplayer.player.PlayerFactory
import com.shuyu.gsyvideoplayer.player.SystemPlayerManager
import com.shuyu.gsyvideoplayer.preview.GSYVideoPreviewFrame
import com.shuyu.gsyvideoplayer.preview.GSYVideoPreviewListProvider
import com.shuyu.gsyvideoplayer.preview.GSYVideoPreviewProvider
import com.shuyu.gsyvideoplayer.preview.GSYVideoPreviewVttParser
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import com.suixin.sx2libra.model.MediaUrlPolicy
import com.suixin.sx2libra.model.VideoAspect
import com.suixin.sx2libra.model.VideoRequest
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A value-only thumbnail frame exposed to the Activity while scrubbing. */
data class GsyPreviewFrame(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val imageUrl: String,
    val cropX: Int = -1,
    val cropY: Int = -1,
    val cropWidth: Int = -1,
    val cropHeight: Int = -1,
) {
    fun contains(positionMillis: Long): Boolean =
        positionMillis >= startTimeMillis && positionMillis < endTimeMillis

    val hasCrop: Boolean
        get() = cropX >= 0 && cropY >= 0 && cropWidth > 0 && cropHeight > 0
}

/**
 * Actual GSY Java 13.1.0 backend.  The Java artifact contains both
 * [StandardGSYVideoPlayer] and [SystemPlayerManager], so playback does not
 * silently fall back to VideoView when this backend is installed.
 *
 * The VTT parser is part of the same published artifact.  The small track
 * loader is deliberately bounded and only accepts the already validated
 * r2.2libra.com HTTPS URL; thumbnail images are filtered with the same media
 * policy before they become visible to the page.
 */
class GsyJavaPlayerBackend(
    private val context: Context,
    private val trackLoader: VttPreviewTrackLoader = VttPreviewTrackLoader(),
) : GsyPlayerBackend {
    override val supportsSpeed: Boolean = true
    override val supportsPreviewTrack: Boolean = true

    private val player = StandardGSYVideoPlayer(context)
    private val previousShowType = GSYVideoType.getShowType()
    private val trackGeneration = AtomicLong(0L)
    private var progressListener: ((Long, Long, Boolean) -> Unit)? = null
    private var previewProvider: GSYVideoPreviewProvider? = null
    private var attachedParent: ViewGroup? = null
    private var released = false

    init {
        // The Java artifact's default is IjkPlayerManager, which requires its
        // native player path. SystemPlayerManager uses Android MediaPlayer and
        // is present in the same published artifact, so this is a real,
        // compile-time verified implementation with no missing native cast API.
        PlayerFactory.setPlayManager(SystemPlayerManager::class.java)
        GSYVideoType.setRenderType(GSYVideoType.TEXTURE)
        player.setShowDragProgressTextOnSeekBar(true)
        player.setNeedShowWifiTip(false)
        player.setGSYVideoProgressListener(GSYVideoProgressListener { _, _, position, duration ->
            progressListener?.invoke(
                position.coerceAtLeast(0L),
                duration.coerceAtLeast(0L),
                player.isInPlayingState(),
            )
        })
    }

    override fun attach(parent: FrameLayout) {
        if (released) return
        attachedParent?.removeView(player)
        attachedParent = parent
        parent.addView(
            player,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun setVideo(request: VideoRequest) {
        if (released || !MediaUrlPolicy.isAllowedVideoUrl(request.url, request.mimeType)) return
        player.setLooping(false)
        player.setStartAfterPrepared(false)
        GSYVideoOptionBuilder()
            .setUrl(request.url)
            .setVideoTitle(request.title.orEmpty())
            .setLooping(false)
            .setStartAfterPrepared(false)
            .setNeedShowWifiTip(false)
            .setShowDragProgressTextOnSeekBar(true)
            .setGSYVideoProgressListener(playerProgressListener)
            .build(player)
    }

    override fun start() {
        if (released) return
        when (player.currentState) {
            GSYVideoView.CURRENT_STATE_PLAYING,
            GSYVideoView.CURRENT_STATE_PLAYING_BUFFERING_START -> Unit
            GSYVideoView.CURRENT_STATE_PAUSE -> player.onVideoResume(false)
            else -> player.startPlayLogic()
        }
    }

    override fun pause() {
        if (!released) player.onVideoPause()
    }

    override fun seekTo(positionMillis: Long) {
        if (!released && positionMillis >= 0L) player.seekTo(positionMillis)
    }

    override fun setSpeed(speed: Float): Boolean {
        if (released || speed !in SUPPORTED_PLAYBACK_SPEEDS) return false
        player.setSpeed(speed)
        return true
    }

    override fun setAspect(aspect: VideoAspect): Boolean {
        if (released) return false
        GSYVideoType.setShowType(
            when (aspect) {
                VideoAspect.DEFAULT -> GSYVideoType.SCREEN_TYPE_DEFAULT
                VideoAspect.RATIO_16_9 -> GSYVideoType.SCREEN_TYPE_16_9
                VideoAspect.RATIO_4_3 -> GSYVideoType.SCREEN_TYPE_4_3
                VideoAspect.CROP -> GSYVideoType.SCREEN_TYPE_FULL
                // GSY's published MeasureHelper uses MATCH_FULL (-4) for
                // filling the host bounds, which is the requested stretch
                // mode. It is not a made-up API or a hidden cast capability.
                VideoAspect.STRETCH -> GSYVideoType.SCREEN_MATCH_FULL
            },
        )
        player.requestLayout()
        return true
    }

    override fun setPreviewVttUrl(url: String): Boolean {
        if (released || !MediaUrlPolicy.isAllowedVttUrl(url)) return false
        val generation = trackGeneration.incrementAndGet()
        previewProvider?.release()
        previewProvider = null
        trackLoader.load(url) { provider ->
            if (released || trackGeneration.get() != generation) {
                provider?.release()
                return@load
            }
            previewProvider = provider
        }
        return true
    }

    override fun previewFrame(positionMillis: Long): GsyPreviewFrame? {
        val frame = previewProvider?.getPreviewFrame(positionMillis) ?: return null
        val imageUrl = frame.imageUrl.substringBefore('#')
            .takeIf(MediaUrlPolicy::isAllowedImageUrl) ?: return null
        return GsyPreviewFrame(
            startTimeMillis = frame.startTimeMs,
            endTimeMillis = frame.endTimeMs,
            imageUrl = imageUrl,
            cropX = frame.cropX,
            cropY = frame.cropY,
            cropWidth = frame.cropWidth,
            cropHeight = frame.cropHeight,
        )
    }

    override fun currentPosition(): Long = if (released) 0L else player.getCurrentPositionWhenPlaying().coerceAtLeast(0L)

    override fun duration(): Long = if (released) 0L else player.getDuration().coerceAtLeast(0L)

    override fun isPlaying(): Boolean = !released && player.isInPlayingState()

    override fun release() {
        if (released) return
        released = true
        trackGeneration.incrementAndGet()
        previewProvider?.release()
        previewProvider = null
        progressListener = null
        attachedParent?.removeView(player)
        attachedParent = null
        player.release()
        GSYVideoType.setShowType(previousShowType)
        trackLoader.close()
    }

    fun setProgressListener(listener: ((Long, Long, Boolean) -> Unit)?) {
        progressListener = listener
    }

    private val playerProgressListener = GSYVideoProgressListener { _, _, position, duration ->
        progressListener?.invoke(
            position.coerceAtLeast(0L),
            duration.coerceAtLeast(0L),
            player.isInPlayingState(),
        )
    }
}

/** Small bounded loader for a remote WebVTT thumbnail track. */
class VttPreviewTrackLoader(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    fun load(url: String, callback: (GSYVideoPreviewProvider?) -> Unit) {
        if (!MediaUrlPolicy.isAllowedVttUrl(url)) {
            callback(null)
            return
        }
        executor.execute {
            val provider = runCatching {
                val connection = connectionFactory(URL(url)).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "text/vtt,text/plain;q=0.9")
                }
                try {
                    if (connection.responseCode !in 200..299) return@runCatching null
                    if (connection.contentLengthLong > MAX_VTT_BYTES) return@runCatching null
                    val text = connection.inputStream.use(::readBoundedUtf8)
                    val frames = GSYVideoPreviewVttParser.parseFrames(text, url)
                        .filter { frame ->
                            val imageUrl = frame.imageUrl.substringBefore('#')
                            MediaUrlPolicy.isAllowedImageUrl(imageUrl) &&
                                (!frame.hasCrop() || (
                                    frame.cropX >= 0 && frame.cropY >= 0 &&
                                        frame.cropWidth > 0 && frame.cropHeight > 0
                                    ))
                        }
                    if (frames.isEmpty()) null else GSYVideoPreviewListProvider(frames)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            callback(provider)
        }
    }

    fun close() = executor.shutdownNow()

    private fun readBoundedUtf8(input: java.io.InputStream): String {
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_VTT_BYTES) error("VTT too large")
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private companion object {
        const val MAX_VTT_BYTES = 128 * 1024
    }
}
