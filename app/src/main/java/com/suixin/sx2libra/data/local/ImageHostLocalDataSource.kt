package com.suixin.sx2libra.data.local

import com.tencent.mmkv.MMKV

/** Small persistence boundary kept independent from the settings ViewModel. */
interface ImageHostDataSource {
    fun readKey(): String?
    fun writeKey(key: String)
}

private class MmkvImageHostDataSource(private val mmkv: MMKV) : ImageHostDataSource {
    override fun readKey(): String? = mmkv.decodeString(ImageHostLocalDataSource.KEY, null)

    override fun writeKey(key: String) {
        mmkv.encode(ImageHostLocalDataSource.KEY, key)
    }
}

/** Independent MMKV namespace for the selected image host. */
class ImageHostLocalDataSource private constructor(
    private val dataSource: ImageHostDataSource,
) : ImageHostDataSource by dataSource {
    constructor() : this(
        MmkvImageHostDataSource(MMKV.mmkvWithID(STORE_ID)),
    )

    /** Visible for JVM tests and non-MMKV persistence adapters. */
    @Suppress("UNUSED_PARAMETER")
    constructor(dataSource: ImageHostDataSource, testOnly: Boolean = true) : this(dataSource)

    companion object {
        const val STORE_ID: String = "image_host"
        const val KEY: String = "image_host_v1"
    }
}
