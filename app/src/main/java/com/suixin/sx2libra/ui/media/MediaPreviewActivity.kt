package com.suixin.sx2libra.ui.media

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.github.chrisbanes.photoview.PhotoView
import com.suixin.sx2libra.R
import com.suixin.sx2libra.data.local.MediaStoreDataSource
import com.suixin.sx2libra.data.repository.MediaRepository
import com.suixin.sx2libra.model.MediaPreviewRequest
import com.suixin.sx2libra.model.MediaSaveResult
import com.suixin.sx2libra.model.MediaUrlPolicy
import com.suixin.sx2libra.ui.system.applySystemBarInsets
import com.suixin.sx2libra.ui.system.enableImmersiveSystemBars

/** Native full-screen image/GIF preview. */
class MediaPreviewActivity : Activity() {
    private lateinit var request: MediaPreviewRequest
    private lateinit var viewModel: MediaPreviewViewModel
    private lateinit var pager: ViewPager2
    private lateinit var pagerAdapter: PreviewPagerAdapter
    private lateinit var counter: TextView
    private var saveDialog: AlertDialog? = null
    private var observerHandle: AutoCloseable? = null
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveSystemBars(lightStatusBars = false, lightNavigationBars = false)
        val urls = intent.getStringArrayListExtra(EXTRA_URLS)
        val index = savedInstanceState?.getInt(KEY_INDEX)
            ?: intent.getIntExtra(EXTRA_INITIAL_INDEX, 0)
        if (urls == null || urls.isEmpty() || urls.size > MediaUrlPolicy.MAX_PREVIEW_ITEMS ||
            urls.any { !MediaUrlPolicy.isAllowedImageUrl(it) } || index !in urls.indices
        ) {
            finish()
            return
        }
        val uniqueUrls = urls.distinct()
        val selectedUrl = urls[index]
        val selectedIndex = uniqueUrls.indexOf(selectedUrl)
        request = MediaPreviewRequest(uniqueUrls, selectedIndex.coerceAtLeast(0))
        viewModel = MediaPreviewViewModel(
            MediaRepository(MediaStoreDataSource(applicationContext)),
            request
        )
        buildUi()
        observerHandle = viewModel.observe(::renderState)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xff000000.toInt())
            isClickable = true
            setOnClickListener { onContentClick() }
        }
        pager = ViewPager2(this).apply {
            setBackgroundColor(0xff000000.toInt())
            offscreenPageLimit = 1
        }
        root.addView(
            pager,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        counter = TextView(this).apply {
            setTextColor(0xffffffff.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            includeFontPadding = false
            val horizontalPadding = resources.getDimensionPixelSize(
                R.dimen.libra_media_counter_padding_horizontal,
            )
            val verticalPadding = resources.getDimensionPixelSize(
                R.dimen.libra_media_counter_padding_vertical,
            )
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            background = ContextCompat.getDrawable(this@MediaPreviewActivity, R.drawable.bg_media_counter)
        }
        root.addView(
            counter,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL }
        )
        setContentView(root)
        counter.applySystemBarInsets(top = true)
        pagerAdapter = PreviewPagerAdapter(
            urls = request.urls,
            createPage = ::createPage,
            onPageBound = ::onPageBound,
        )
        pager.adapter = pagerAdapter
        pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position != viewModel.currentState().currentIndex) viewModel.select(position)
                updateCounter(position)
                loadPage(position)
            }
        }.also(pager::registerOnPageChangeCallback)
        pager.setCurrentItem(request.initialIndex, false)
        updateCounter(request.initialIndex)
        loadPage(request.initialIndex)
    }

    private fun createPage(index: Int, url: String): View {
        val page = FrameLayout(this).apply { setBackgroundColor(0xff000000.toInt()) }
        val image = createPhotoView()
        image.tag = PageTag(index, url)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setOnClickListener { onContentClick() }
        image.setOnLongClickListener {
            showActions()
            true
        }
        page.addView(
            image,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val progress = ProgressBar(this).apply {
            tag = PROGRESS_TAG
            indeterminateTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@MediaPreviewActivity, R.color.libra_primary),
            )
        }
        page.addView(
            progress,
            FrameLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.libra_media_progress_size),
                resources.getDimensionPixelSize(R.dimen.libra_media_progress_size),
            ).apply { gravity = android.view.Gravity.CENTER }
        )
        val error = TextView(this).apply {
            tag = ERROR_TAG
            setTextColor(0xffffffff.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            includeFontPadding = false
            text = "图片加载失败，点击重试"
            visibility = View.GONE
            setOnClickListener { loadPage(index, page) }
        }
        page.addView(
            error,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER }
        )
        return page
    }

    private fun onPageBound(index: Int, page: View) {
        loadPage(index, page)
    }

    private fun createPhotoView(): PhotoView = PhotoView(this)

    private fun loadPage(index: Int, boundPage: View? = null) {
        if (index !in request.urls.indices) return
        val page = boundPage ?: pagerAdapter.pageAt(index) ?: return
        if (page !is ViewGroup) return
        val image = page.getChildAt(0) as? PhotoView ?: return
        val progress = page.findViewWithTag<ProgressBar>(PROGRESS_TAG)
        val error = page.findViewWithTag<TextView>(ERROR_TAG)
        progress?.visibility = View.VISIBLE
        error?.visibility = View.GONE
        if (!MediaUrlPolicy.isAllowedImageUrl(request.urls[index])) {
            progress?.visibility = View.GONE
            error?.visibility = View.VISIBLE
            return
        }
        Glide.with(this)
            .load(request.urls[index])
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    progress?.visibility = View.GONE
                    error?.visibility = View.VISIBLE
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: Target<android.graphics.drawable.Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    progress?.visibility = View.GONE
                    error?.visibility = View.GONE
                    return false
                }
            })
            .into(image)
    }

    private fun renderState(state: MediaPreviewUiState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { renderState(state) }
            return
        }
        if (isFinishing || isDestroyed) return
        updateCounter(state.currentIndex)
        state.saveResult?.let { result ->
            when (result) {
                is MediaSaveResult.Saved -> Toast.makeText(this, "已保存到 ${result.displayName}", Toast.LENGTH_SHORT).show()
                is MediaSaveResult.Failed -> Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateCounter(index: Int) {
        if (::counter.isInitialized) counter.text = "${index + 1}/${request.urls.size}"
    }

    private fun onContentClick() {
        if (saveDialog?.isShowing == true) saveDialog?.dismiss() else finish()
    }

    private fun showActions() {
        if (saveDialog?.isShowing == true) return
        saveDialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Sx2libra_MaterialAlertDialog)
            .setItems(arrayOf("保存图片", "取消")) { _, which ->
                if (which == 0) {
                    val current = viewModel.currentState().currentIndex
                    viewModel.saveCurrentImage(fileName(request.urls[current]))
                }
            }
            .create()
            .also { it.show() }
    }

    private fun fileName(url: String): String =
        url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() } ?: "2libra-image"

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_INDEX, if (::viewModel.isInitialized) viewModel.currentState().currentIndex else 0)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android API 33; retained for API 26")
    override fun onBackPressed() {
        if (saveDialog?.isShowing == true) saveDialog?.dismiss() else super.onBackPressed()
    }

    override fun onDestroy() {
        saveDialog?.dismiss()
        observerHandle?.close()
        if (::pager.isInitialized) {
            pageChangeCallback?.let(pager::unregisterOnPageChangeCallback)
            pager.adapter = null
        }
        if (::viewModel.isInitialized) viewModel.clear()
        super.onDestroy()
    }

    private data class PageTag(val index: Int, val url: String)

    private class PreviewPagerAdapter(
        private val urls: List<String>,
        private val createPage: (Int, String) -> View,
        private val onPageBound: (Int, View) -> Unit,
    ) : RecyclerView.Adapter<PreviewPagerAdapter.Holder>() {
        private val pages = HashMap<Int, View>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val container = FrameLayout(parent.context)
            container.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            return Holder(container)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.container.removeAllViews()
            val page = createPage(position, urls[position])
            holder.container.addView(
                page,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            pages[position] = page
            onPageBound(position, page)
        }

        override fun onViewRecycled(holder: Holder) {
            val iterator = pages.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.parent === holder.container) iterator.remove()
            }
            for (index in 0 until holder.container.childCount) {
                (holder.container.getChildAt(index) as? ViewGroup)?.let { page ->
                    for (childIndex in 0 until page.childCount) {
                        (page.getChildAt(childIndex) as? PhotoView)?.let { image ->
                            Glide.with(holder.container).clear(image)
                        }
                    }
                }
            }
            holder.container.removeAllViews()
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = urls.size

        fun pageAt(index: Int): View? = pages[index]

        class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container)
    }

    companion object {
        const val EXTRA_URLS = "com.suixin.sx2libra.media.urls"
        const val EXTRA_INITIAL_INDEX = "com.suixin.sx2libra.media.initial_index"
        private const val KEY_INDEX = "media.current_index"
        private const val PROGRESS_TAG = "media.progress"
        private const val ERROR_TAG = "media.error"
    }
}
