package com.suixin.sx2libra.data.repository

import com.suixin.sx2libra.data.local.ImageHostDataSource
import com.suixin.sx2libra.model.ImageHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application-scoped selected image host; one upload batch snapshots its value. */
class ImageHostRepository(
    private val dataSource: ImageHostDataSource,
) {
    private val initialHost = readInitialHost()
    private val _selectedHost = MutableStateFlow(initialHost)
    val selectedHost: StateFlow<ImageHost> = _selectedHost.asStateFlow()

    init {
        // Repair missing/invalid values so future launches see the canonical default.
        runCatching { dataSource.writeKey(initialHost.key) }
    }

    fun select(host: ImageHost): Result<Unit> = runCatching {
        dataSource.writeKey(host.key)
        _selectedHost.value = host
    }

    private fun readInitialHost(): ImageHost {
        val stored = runCatching { dataSource.readKey() }.getOrNull()
        return ImageHost.fromStoredKey(stored)
    }
}
