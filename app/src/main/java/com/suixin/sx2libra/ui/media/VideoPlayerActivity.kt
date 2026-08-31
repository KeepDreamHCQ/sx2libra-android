package com.suixin.sx2libra.ui.media

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.suixin.sx2libra.R
import com.suixin.sx2libra.model.MediaUrlPolicy
import com.suixin.sx2libra.model.PlaybackSnapshot
import com.suixin.sx2libra.model.VideoAspect
import com.suixin.sx2libra.model.VideoRequest
import com.suixin.sx2libra.ui.system.applySystemBarInsets
import com.suixin.sx2libra.ui.system.enableImmersiveSystemBars
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

/**
 * Activity-level owner for the player instance. The default backend is the
 * published GSY Java 13.1.0 adapter; callers may replace it through
 * [VideoPlayerBackendRegistry] when they have a separately verified backend.
 * Unsupported controls are reported instead of simulated.
 */
class VideoPlayerActivity : Activity() {
    private lateinit var request: VideoRequest
    private lateinit var viewModel: VideoPlayerViewModel
    private lateinit var player: LibraGSYVideoPlayer
    private lateinit var seekBar: SeekBar
    private lateinit var previewThumbnail: ImageView
    private var observerHandle: AutoCloseable? = null
    private var userSeeking = false
    private var castDialog: AlertDialog? = null
    private var resumePlaybackAfterPause = false
    private var previewBitmapTarget: CustomTarget<Bitmap>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveSystemBars(lightStatusBars = false, lightNavigationBars = false)
        val url = intent.getStringExtra(EXTRA_URL)
        val mime = intent.getStringExtra(EXTRA_MIME_TYPE)
        if (url.isNullOrBlank() || mime.isNullOrBlank()) {
            finish()
            return
        }
        val candidate = VideoRequest(
            url = url,
            mimeType = mime,
            title = intent.getStringExtra(EXTRA_TITLE),
            posterUrl = intent.getStringExtra(EXTRA_POSTER_URL),
            previewVttUrl = intent.getStringExtra(EXTRA_PREVIEW_VTT_URL)
        )
        if (!isValid(candidate)) {
            finish()
            return
        }
        request = candidate
        viewModel = VideoPlayerViewModel(request, VideoPlayerBackendRegistry.castController())
        buildUi()
        observerHandle = viewModel.observe(::renderState)
        if (!player.setVideo(request)) {
            finish()
            return
        }
        val previewVttUrl = request.previewVttUrl
        if (previewVttUrl != null && !player.setPreviewVttUrl(previewVttUrl)) {
            Toast.makeText(this, "该视频暂不支持进度预览", Toast.LENGTH_SHORT).show()
        }
        player.startPlayback()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff000000.toInt())
        }
        val playerHost = FrameLayout(this)
        player = LibraGSYVideoPlayer(this, gsyBackend = VideoPlayerBackendRegistry.gsyBackend(this))
        playerHost.addView(player, FrameLayout.LayoutParams(-1, -1))
        previewThumbnail = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xcc000000.toInt())
        }
        playerHost.addView(
            previewThumbnail,
            FrameLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.libra_video_preview_width),
                resources.getDimensionPixelSize(R.dimen.libra_video_preview_height),
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = resources.getDimensionPixelSize(R.dimen.libra_video_preview_top)
            },
        )
        root.addView(playerHost, LinearLayout.LayoutParams(-1, 0, 1f))
        seekBar = SeekBar(this).apply {
            max = 1000
            val primary = ContextCompat.getColor(this@VideoPlayerActivity, R.color.libra_primary)
            progressTintList = ColorStateList.valueOf(primary)
            thumbTintList = ColorStateList.valueOf(primary)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser || !userSeeking) return
                    val duration = player.duration()
                    val frame = if (duration > 0L) {
                        player.previewFrame(duration * value / bar.max)
                    } else {
                        null
                    }
                    if (frame == null) {
                        clearPreviewThumbnail()
                    } else {
                        previewThumbnail.visibility = View.VISIBLE
                        loadPreviewThumbnail(frame)
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    userSeeking = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    userSeeking = false
                    clearPreviewThumbnail()
                    val duration = player.duration()
                    if (duration > 0L) player.seekTo(duration * bar.progress / bar.max)
                }
            })
        }
        root.addView(
            seekBar,
            LinearLayout.LayoutParams(
                -1,
                resources.getDimensionPixelSize(R.dimen.libra_video_seekbar_height),
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val horizontalPadding = resources.getDimensionPixelSize(R.dimen.libra_media_control_padding)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            setBackgroundColor(ContextCompat.getColor(this@VideoPlayerActivity, R.color.media_control_surface))
        }
        controls.addView(button("播放/暂停") { togglePlayback() })
        controls.addView(button("倍速") { showSpeedMenu() })
        controls.addView(button("画幅") { showAspectMenu() })
        controls.addView(button("投屏") { viewModel.startDiscovery() })
        root.addView(
            controls,
            LinearLayout.LayoutParams(
                -1,
                resources.getDimensionPixelSize(R.dimen.libra_media_controls_height),
            ),
        )
        setContentView(root)
        controls.applySystemBarInsets(bottom = true)
        player.setProgressListener { position, duration, playing ->
            if (!userSeeking && duration > 0L) {
                seekBar.progress = (position * seekBar.max / duration).toInt().coerceIn(0, seekBar.max)
            }
            viewModel.onPlaybackSnapshot(
                PlaybackSnapshot(
                    positionMillis = position.coerceAtLeast(0L),
                    durationMillis = duration.coerceAtLeast(0L),
                    isPlaying = playing,
                    isBuffering = false,
                    speed = viewModel.currentState().snapshot.speed,
                    aspect = viewModel.currentState().snapshot.aspect
                )
            )
        }
    }

    private fun button(label: String, click: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        isAllCaps = false
        textSize = 13f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val contentColor = ContextCompat.getColor(this@VideoPlayerActivity, R.color.media_control_content)
        setTextColor(contentColor)
        backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this@VideoPlayerActivity, R.color.media_control_surface),
        )
        strokeWidth = 1
        strokeColor = ColorStateList.valueOf(Color.argb(76, 255, 255, 255))
        cornerRadius = resources.getDimensionPixelSize(R.dimen.libra_radius_field)
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.libra_icon_button_padding)
        setPadding(horizontalPadding, 0, horizontalPadding, 0)
        setOnClickListener { click() }
    }

    private fun togglePlayback() {
        if (player.isPlaying()) player.pausePlayback() else player.startPlayback()
    }

    private fun showSpeedMenu() {
        val labels = SUPPORTED_PLAYBACK_SPEEDS.map { "${it}×" }.toTypedArray()
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Sx2libra_MaterialAlertDialog)
            .setTitle("播放速度")
            .setItems(labels) { _, which ->
                val speed = SUPPORTED_PLAYBACK_SPEEDS[which]
                if (!player.setPlaybackSpeed(speed) || !viewModel.setSpeed(speed)) {
                    Toast.makeText(this, "当前播放器不支持倍速", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showAspectMenu() {
        val aspects = arrayOf("原始适配", "16:9", "4:3", "裁剪铺满", "拉伸铺满")
        val values = VideoAspect.entries.toTypedArray()
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Sx2libra_MaterialAlertDialog)
            .setTitle("画幅")
            .setItems(aspects) { _, which ->
                if (!player.setAspect(values[which]) || !viewModel.setAspect(values[which])) {
                    Toast.makeText(this, "当前播放器不支持画幅切换", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun renderState(state: VideoPlayerUiState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { renderState(state) }
            return
        }
        if (isFinishing || isDestroyed) return
        when (val cast = state.castState) {
            is CastState.Devices -> showDevices(cast.devices)
            is CastState.Failed -> {
                if (cast.reason == CastFailure.PROVIDER_UNAVAILABLE) {
                    Toast.makeText(this, "当前版本未提供投屏组件", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "投屏失败，已保留本地播放", Toast.LENGTH_SHORT).show()
                }
            }
            is CastState.Connected -> player.pausePlayback()
            else -> Unit
        }
    }

    private fun showDevices(devices: List<CastDevice>) {
        if (devices.isEmpty() || castDialog?.isShowing == true) return
        castDialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Sx2libra_MaterialAlertDialog)
            .setTitle("选择投屏设备")
            .setItems(devices.map { it.name }.toTypedArray()) { _, which ->
                viewModel.connect(devices[which])
            }
            .setOnCancelListener { viewModel.stopDiscovery() }
            .create()
            .also { it.show() }
    }

    private fun isValid(candidate: VideoRequest): Boolean =
        MediaUrlPolicy.isAllowedVideoMime(candidate.mimeType) &&
            MediaUrlPolicy.isAllowedVideoUrl(candidate.url, candidate.mimeType) &&
            (candidate.posterUrl == null || MediaUrlPolicy.isAllowedPosterUrl(candidate.posterUrl)) &&
            (candidate.previewVttUrl == null || MediaUrlPolicy.isAllowedVttUrl(candidate.previewVttUrl)) &&
            (candidate.title == null || candidate.title.length <= 160)

    override fun onPause() {
        resumePlaybackAfterPause = ::player.isInitialized && player.isPlaying()
        if (::player.isInitialized) player.pausePlayback()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (resumePlaybackAfterPause && ::player.isInitialized && !isFinishing &&
            viewModel.currentState().castState !is CastState.Connected
        ) {
            resumePlaybackAfterPause = false
            player.startPlayback()
        }
    }

    @Deprecated("Deprecated in Android API 33; retained for API 26")
    override fun onBackPressed() {
        if (viewModel.currentState().castState is CastState.Connected) viewModel.disconnectCast()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        castDialog?.dismiss()
        observerHandle?.close()
        if (::viewModel.isInitialized) viewModel.clear()
        if (::player.isInitialized) player.release()
        clearPreviewThumbnail()
        super.onDestroy()
    }

    private fun loadPreviewThumbnail(frame: GsyPreviewFrame) {
        previewBitmapTarget?.let { Glide.with(this).clear(it) }
        previewBitmapTarget = null
        if (!frame.hasCrop) {
            Glide.with(this)
                .load(frame.imageUrl)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .into(previewThumbnail)
            return
        }
        val target = object : CustomTarget<Bitmap>(240, 135) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                val left = frame.cropX.coerceIn(0, resource.width - 1)
                val top = frame.cropY.coerceIn(0, resource.height - 1)
                val width = frame.cropWidth.coerceAtMost(resource.width - left)
                val height = frame.cropHeight.coerceAtMost(resource.height - top)
                if (width > 0 && height > 0) {
                    previewThumbnail.setImageBitmap(Bitmap.createBitmap(resource, left, top, width, height))
                } else {
                    previewThumbnail.visibility = View.GONE
                }
            }

            override fun onLoadCleared(placeholder: Drawable?) = Unit
        }
        previewBitmapTarget = target
        Glide.with(this)
            .asBitmap()
            .load(frame.imageUrl)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .into(target)
    }

    private fun clearPreviewThumbnail() {
        if (!::previewThumbnail.isInitialized) return
        previewBitmapTarget?.let { Glide.with(this).clear(it) }
        previewBitmapTarget = null
        Glide.with(this).clear(previewThumbnail)
        previewThumbnail.visibility = View.GONE
    }

    companion object {
        const val EXTRA_URL = "com.suixin.sx2libra.video.url"
        const val EXTRA_MIME_TYPE = "com.suixin.sx2libra.video.mime_type"
        const val EXTRA_TITLE = "com.suixin.sx2libra.video.title"
        const val EXTRA_POSTER_URL = "com.suixin.sx2libra.video.poster_url"
        const val EXTRA_PREVIEW_VTT_URL = "com.suixin.sx2libra.video.preview_vtt_url"
    }
}

/** One place for the host application to install verified player providers. */
object VideoPlayerBackendRegistry {
    @Volatile
    var gsyFactory: ((android.content.Context) -> GsyPlayerBackend?)? = { context ->
        GsyJavaPlayerBackend(context)
    }
    @Volatile
    var castFactory: (() -> VideoCastController)? = null

    fun gsyBackend(context: android.content.Context): GsyPlayerBackend? = gsyFactory?.invoke(context)
    fun castController(): VideoCastController = castFactory?.invoke() ?: UnavailableVideoCastController()
}
