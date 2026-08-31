package com.suixin.sx2libra.data.repository

import com.suixin.sx2libra.data.remote.ImageUploadRemoteDataSource
import com.suixin.sx2libra.data.remote.UploadCall
import com.suixin.sx2libra.data.remote.UploadCallback
import com.suixin.sx2libra.model.ImageMimeTypes
import com.suixin.sx2libra.model.ImageUploadEvent
import com.suixin.sx2libra.model.ImageUploadLimits
import com.suixin.sx2libra.model.SelectedImage
import com.suixin.sx2libra.model.UploadErrorCode
import com.suixin.sx2libra.model.UploadProgress
import com.suixin.sx2libra.model.UploadTaskSnapshot
import com.suixin.sx2libra.model.UploadTaskStatus
import com.suixin.sx2libra.model.UploadTicket
import com.suixin.sx2libra.model.MediaUrlPolicy
import com.suixin.sx2libra.model.newUploadClientId
import java.io.InputStream
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min

fun interface ImageBodyProvider {
    fun open(image: SelectedImage): InputStream
}

fun interface ImageUploadObserver {
    fun onEvent(event: ImageUploadEvent)
}

internal interface UploadBatchController {
    fun cancel()
    fun retry(clientId: String): Boolean
    fun snapshots(): List<UploadTaskSnapshot>
}

private class UploadTask(val clientId: String, val image: SelectedImage) {
    var state: UploadTaskStatus = UploadTaskStatus.SELECTED
    var error: UploadErrorCode? = null
    var url: String? = null
    var progress: UploadProgress? = null
    var lastProgressNanos: Long = 0L
    var wasActive: Boolean = false
    var call: UploadCall? = null
    var future: Future<*>? = null
}

/** Handle for one foreground upload batch. It contains no Activity or WebView. */
class UploadBatchHandle internal constructor(private val batch: UploadBatchController) {
    fun cancel() = batch.cancel()

    /** Retries one failed item with its original clientId. */
    fun retry(clientId: String): Boolean = batch.retry(clientId)

    fun snapshots(): List<UploadTaskSnapshot> = batch.snapshots()
}

/**
 * Foreground upload queue. A batch owns at most three active requests and is
 * intentionally not persisted or moved to WorkManager.
 */
class ImageUploadRepository(
    private val remote: ImageUploadRemoteDataSource,
    private val bodyProvider: ImageBodyProvider,
    private val executor: ExecutorService = Executors.newFixedThreadPool(
        ImageUploadLimits.MAX_CONCURRENT_UPLOADS
    )
) {
    fun startBatch(
        requestId: String,
        ticket: UploadTicket,
        images: List<SelectedImage>,
        observer: ImageUploadObserver
    ): UploadBatchHandle {
        require(requestId.length in 1..128)
        val batch = UploadBatch(requestId, ticket, images, observer)
        batch.start()
        return UploadBatchHandle(batch)
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private inner class UploadBatch(
        private val requestId: String,
        private val ticket: UploadTicket,
        images: List<SelectedImage>,
        private val observer: ImageUploadObserver
    ) : UploadBatchController {
        private val lock = Any()
        private val tasks = LinkedHashMap<String, UploadTask>()
        private val pending = ArrayDeque<UploadTask>()
        private var active = 0
        private var cancelled = false
        private var finishEmitted = false

        init {
            // The first nine values are the only ones eligible for this batch;
            // this prevents an untrusted H5 payload from growing the queue.
            images.take(ticket.effectiveMaxFiles()).forEachIndexed { index, image ->
                val clientId = newUploadClientId()
                tasks[clientId] = UploadTask(clientId, image.copy(selectionIndex = index))
            }
        }

        fun start() {
            val initialEvents = mutableListOf<ImageUploadEvent>()
            var emptyBatchFinished = false
            synchronized(lock) {
                if (tasks.isEmpty()) {
                    finishEmitted = true
                    emptyBatchFinished = true
                }
                for (task in tasks.values) {
                    task.state = UploadTaskStatus.SELECTED
                    initialEvents += ImageUploadEvent.Selected(
                        requestId,
                        task.clientId,
                        task.image.selectionIndex,
                        safeDisplayName(task.image.displayName)
                    )
                    val error = validationError(task.image)
                    if (error == null) {
                        task.state = UploadTaskStatus.QUEUED
                        pending.addLast(task)
                        initialEvents += ImageUploadEvent.Queued(requestId, task.clientId)
                    } else {
                        task.state = UploadTaskStatus.FAILED
                        task.error = error
                        initialEvents += ImageUploadEvent.Failed(
                            requestId,
                            task.clientId,
                            error,
                            retryable = error != UploadErrorCode.TICKET_EXPIRED
                        )
                    }
                }
            }
            initialEvents.forEach(::emit)
            if (emptyBatchFinished) {
                emit(ImageUploadEvent.BatchFinished(requestId, emptyList(), emptyList()))
                return
            }
            pump()
            maybeFinish()
        }

        override fun cancel() {
            val events = mutableListOf<ImageUploadEvent>()
            val calls = mutableListOf<UploadCall>()
            synchronized(lock) {
                if (cancelled || finishEmitted) return
                cancelled = true
                pending.clear()
                for (task in tasks.values) {
                    if (task.state == UploadTaskStatus.SELECTED ||
                        task.state == UploadTaskStatus.QUEUED ||
                        task.state == UploadTaskStatus.UPLOADING
                    ) {
                        task.state = UploadTaskStatus.CANCELLED
                        task.error = UploadErrorCode.USER_CANCELLED
                        task.future?.cancel(true)
                        task.call?.let(calls::add)
                        events += ImageUploadEvent.Cancelled(requestId, task.clientId)
                        if (task.wasActive) {
                            task.wasActive = false
                            active = max(0, active - 1)
                        }
                    }
                }
                finishEmitted = true
            }
            calls.forEach { it.cancel() }
            events.forEach(::emit)
            emit(ImageUploadEvent.BatchCancelled(requestId))
        }

        override fun retry(clientId: String): Boolean {
            synchronized(lock) {
                if (cancelled) return false
                val found = tasks[clientId] ?: return false
                if (found.state != UploadTaskStatus.FAILED ||
                    found.error == UploadErrorCode.TICKET_EXPIRED ||
                    !ticket.isUsable()
                ) return false
                found.error = null
                found.state = UploadTaskStatus.QUEUED
                pending.addLast(found)
                finishEmitted = false
            }
            emit(ImageUploadEvent.Queued(requestId, clientId))
            pump()
            return true
        }

        override fun snapshots(): List<UploadTaskSnapshot> = synchronized(lock) {
            tasks.values.map { task ->
                UploadTaskSnapshot(
                    clientId = task.clientId,
                    selectionIndex = task.image.selectionIndex,
                    displayName = safeDisplayName(task.image.displayName),
                    status = task.state,
                    progress = task.progress,
                    url = task.url,
                    error = task.error
                )
            }
        }

        private fun pump() {
            val toStart = mutableListOf<UploadTask>()
            synchronized(lock) {
                while (!cancelled && active < ImageUploadLimits.MAX_CONCURRENT_UPLOADS && pending.isNotEmpty()) {
                    val task = pending.removeFirst()
                    if (task.state != UploadTaskStatus.QUEUED) continue
                    task.state = UploadTaskStatus.UPLOADING
                    task.wasActive = true
                    active++
                    toStart += task
                }
            }
            toStart.forEach { task ->
                val future = executor.submit { runTask(task) }
                synchronized(lock) {
                    task.future = future
                    if (cancelled && task.state == UploadTaskStatus.UPLOADING) {
                        task.future?.cancel(true)
                    }
                }
            }
        }

        private fun runTask(task: UploadTask) {
            var body: InputStream? = null
            try {
                emit(ImageUploadEvent.Started(requestId, task.clientId))
                body = bodyProvider.open(task.image)
                val call = remote.upload(
                    ticket,
                    task.clientId,
                    task.image,
                    body,
                    object : UploadCallback {
                        override fun onProgress(completedBytes: Long, totalBytes: Long) {
                            onProgress(task, completedBytes, totalBytes)
                        }

                        override fun onSuccess(response: com.suixin.sx2libra.data.remote.ImageUploadResponse) {
                            if (response.clientId != task.clientId ||
                                !MediaUrlPolicy.isTrustedCdnUrl(response.url)
                            ) {
                                completeFailure(task, UploadErrorCode.UPLOAD_REJECTED, retryable = true)
                            } else {
                                completeSuccess(task, response.url)
                            }
                        }

                        override fun onFailure(error: UploadErrorCode) {
                            completeFailure(
                                task,
                                error,
                                retryable = error != UploadErrorCode.TICKET_EXPIRED &&
                                    error != UploadErrorCode.USER_CANCELLED
                            )
                        }
                    }
                )
                synchronized(lock) {
                    task.call = call
                    if (cancelled && task.state == UploadTaskStatus.UPLOADING) call.cancel()
                }
            } catch (_: SecurityException) {
                completeFailure(task, UploadErrorCode.INVALID_IMAGE, retryable = false)
            } catch (_: Exception) {
                completeFailure(
                    task,
                    if (isCancelled(task)) UploadErrorCode.USER_CANCELLED else UploadErrorCode.NETWORK_ERROR,
                    retryable = !isCancelled(task)
                )
            } finally {
                try {
                    body?.close()
                } catch (_: Exception) {
                    // The upload result, not close noise, is exposed to H5.
                }
            }
        }

        private fun onProgress(task: UploadTask, completed: Long, total: Long) {
            val safeTotal = max(0L, total)
            val safeCompleted = completed.coerceIn(0L, safeTotal)
            val now = System.nanoTime()
            val finalUpdate = safeTotal > 0L && safeCompleted >= safeTotal
            synchronized(lock) {
                if (cancelled || task.state != UploadTaskStatus.UPLOADING) return
                if (!finalUpdate && task.lastProgressNanos != 0L &&
                    now - task.lastProgressNanos < ImageUploadLimits.PROGRESS_THROTTLE_MILLIS * 1_000_000L
                ) return
                task.lastProgressNanos = now
                task.progress = UploadProgress(
                    task.clientId,
                    safeCompleted,
                    safeTotal,
                    if (safeTotal == 0L) 0f else (safeCompleted.toDouble() / safeTotal).toFloat().coerceIn(0f, 1f)
                )
            }
            emit(ImageUploadEvent.Progressed(requestId, task.clientId, task.progress!!))
        }

        private fun completeSuccess(task: UploadTask, url: String) {
            val shouldEmit: Boolean
            synchronized(lock) {
                if (task.state != UploadTaskStatus.UPLOADING || cancelled) return
                task.state = UploadTaskStatus.UPLOADED
                task.url = url
                task.error = null
                releaseActive(task)
                shouldEmit = true
            }
            if (shouldEmit) {
                val title = safeDisplayName(task.image.displayName)
                emit(ImageUploadEvent.Completed(requestId, task.clientId, "[$title]($url)"))
                pump()
                maybeFinish()
            }
        }

        private fun completeFailure(task: UploadTask, error: UploadErrorCode, retryable: Boolean) {
            val shouldEmit: Boolean
            synchronized(lock) {
                if (task.state != UploadTaskStatus.UPLOADING || cancelled) return
                task.state = UploadTaskStatus.FAILED
                task.error = error
                releaseActive(task)
                shouldEmit = true
            }
            if (shouldEmit) {
                emit(ImageUploadEvent.Failed(requestId, task.clientId, error, retryable))
                pump()
                maybeFinish()
            }
        }

        private fun releaseActive(task: UploadTask) {
            if (task.wasActive) {
                task.wasActive = false
                active = max(0, active - 1)
            }
        }

        private fun maybeFinish() {
            val result: ImageUploadEvent.BatchFinished?
            synchronized(lock) {
                if (cancelled || finishEmitted || pending.isNotEmpty() || active != 0) return
                finishEmitted = true
                result = ImageUploadEvent.BatchFinished(
                    requestId,
                    tasks.values.filter { it.state == UploadTaskStatus.UPLOADED }.map { it.clientId },
                    tasks.values.filter { it.state == UploadTaskStatus.FAILED }.map { it.clientId }
                )
            }
            result?.let(::emit)
        }

        private fun validationError(image: SelectedImage): UploadErrorCode? {
            if (!ticket.isUsable()) return UploadErrorCode.TICKET_EXPIRED
            if (!image.isStructurallyValid()) {
                return if (image.normalizedMimeType != null && image.bytes > ImageUploadLimits.maxBytesForMime(image.normalizedMimeType)) {
                    UploadErrorCode.FILE_TOO_LARGE
                } else {
                    UploadErrorCode.INVALID_IMAGE
                }
            }
            if (image.bytes > ticket.effectiveMaxBytes()) return UploadErrorCode.FILE_TOO_LARGE
            if (image.normalizedMimeType == null ||
                image.normalizedMimeType !in ticket.allowedMimeTypes.mapNotNull(ImageMimeTypes::normalize).toSet()
            ) return UploadErrorCode.INVALID_IMAGE
            return null
        }

        private fun isCancelled(task: UploadTask): Boolean = synchronized(lock) {
            cancelled || task.state == UploadTaskStatus.CANCELLED || Thread.currentThread().isInterrupted
        }

        private fun emit(event: ImageUploadEvent) {
            try {
                observer.onEvent(event)
            } catch (_: RuntimeException) {
                // A UI observer cannot corrupt queue state or cancel siblings.
            }
        }

        private fun safeDisplayName(value: String): String = value
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifEmpty { "image" }

    }
}
