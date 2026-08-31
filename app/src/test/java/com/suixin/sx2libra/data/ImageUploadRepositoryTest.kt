package com.suixin.sx2libra.data

import com.suixin.sx2libra.data.remote.ImageUploadRemoteDataSource
import com.suixin.sx2libra.data.remote.ImageUploadResponse
import com.suixin.sx2libra.data.remote.UploadCall
import com.suixin.sx2libra.data.remote.UploadCallback
import com.suixin.sx2libra.data.repository.ImageBodyProvider
import com.suixin.sx2libra.data.repository.ImageUploadObserver
import com.suixin.sx2libra.data.repository.ImageUploadRepository
import com.suixin.sx2libra.model.ImageUploadEvent
import com.suixin.sx2libra.model.SelectedImage
import com.suixin.sx2libra.model.UploadErrorCode
import com.suixin.sx2libra.model.UploadTicket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ImageUploadRepositoryTest {
    private lateinit var repository: ImageUploadRepository
    private lateinit var remote: ControlledRemote

    @Before
    fun setUp() {
        remote = ControlledRemote()
        repository = ImageUploadRepository(
            remote = remote,
            bodyProvider = ImageBodyProvider { ByteArrayInputStream(ByteArray(8) { 1 }) }
        )
    }

    @After
    fun tearDown() {
        repository.shutdown()
    }

    @Test
    fun queueNeverExceedsThreeAndPreservesClientIdsOnCompletion() {
        val images = (0 until 9).map { image(it) }
        val events = Collections.synchronizedList(mutableListOf<ImageUploadEvent>())
        val finished = CountDownLatch(1)
        val handle = repository.startBatch(
            requestId = "request",
            ticket = ticket(),
            images = images,
            observer = ImageUploadObserver {
                events += it
                if (it is ImageUploadEvent.BatchFinished) finished.countDown()
            }
        )
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(remote.maxActive.get() <= 3)
        assertEquals(9, events.filterIsInstance<ImageUploadEvent.Selected>().size)
        assertEquals(9, events.filterIsInstance<ImageUploadEvent.Completed>().size)
        assertTrue(
            events.filterIsInstance<ImageUploadEvent.Completed>().all {
                it.markdown.startsWith("[image-") && it.markdown.contains("](https://r2.2libra.com/i/")
            }
        )
        assertEquals(9, handle.snapshots().count { it.status.name == "UPLOADED" })
    }

    @Test
    fun failedItemCanRetryAfterFirstBatchFinishedUsingSameClientId() {
        remote.failFirst = true
        val events = Collections.synchronizedList(mutableListOf<ImageUploadEvent>())
        val finished = CountDownLatch(1)
        val finishedRounds = CountDownLatch(2)
        val completed = CountDownLatch(1)
        val handle = repository.startBatch(
            requestId = "retry-request",
            ticket = ticket(),
            images = listOf(image(0)),
            observer = ImageUploadObserver {
                events += it
                if (it is ImageUploadEvent.BatchFinished) {
                    finished.countDown()
                    finishedRounds.countDown()
                }
                if (it is ImageUploadEvent.Completed) completed.countDown()
            }
        )
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        val failed = snapshot(events).filterIsInstance<ImageUploadEvent.Failed>().single()
        assertTrue(handle.retry(failed.clientId))
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertTrue(
            snapshot(events).filterIsInstance<ImageUploadEvent.Completed>().any { it.clientId == failed.clientId }
        )
        assertTrue(finishedRounds.await(5, TimeUnit.SECONDS))
        assertEquals(2, snapshot(events).filterIsInstance<ImageUploadEvent.BatchFinished>().size)
        assertFalse(handle.retry(failed.clientId))
    }

    @Test
    fun cancelEmitsOneCancellationPerActiveItemAndNoCompletion() {
        remote.delayMillis = 500
        val events = Collections.synchronizedList(mutableListOf<ImageUploadEvent>())
        val started = CountDownLatch(1)
        val handle = repository.startBatch(
            requestId = "cancel-request",
            ticket = ticket(),
            images = (0 until 6).map(::image),
            observer = ImageUploadObserver {
                events += it
                if (it is ImageUploadEvent.Started) started.countDown()
            }
        )
        assertTrue(started.await(5, TimeUnit.SECONDS))
        handle.cancel()
        handle.cancel()
        Thread.sleep(100)
        assertEquals(1, events.filterIsInstance<ImageUploadEvent.BatchCancelled>().size)
        assertTrue(events.filterIsInstance<ImageUploadEvent.Completed>().isEmpty())
        assertEquals(
            events.filterIsInstance<ImageUploadEvent.Cancelled>().map { it.clientId }.distinct().size,
            events.filterIsInstance<ImageUploadEvent.Cancelled>().size
        )
    }

    private fun image(index: Int) = SelectedImage(
        contentUri = "content://test/image-$index",
        mimeType = "image/png",
        bytes = 8,
        displayName = "image-$index.png",
        selectionIndex = index
    )

    private fun ticket() = UploadTicket(
        opaqueValue = "opaque-ticket",
        expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
        maxBytesPerFile = 1024,
        maxFiles = 9
    )

    private fun snapshot(events: List<ImageUploadEvent>): List<ImageUploadEvent> =
        synchronized(events) { events.toList() }

    private class ControlledRemote : ImageUploadRemoteDataSource {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        var delayMillis = 20L
        var failFirst = false
        private val attempts = AtomicInteger(0)

        override fun upload(
            ticket: UploadTicket,
            clientId: String,
            image: SelectedImage,
            body: InputStream,
            callback: UploadCallback
        ): UploadCall {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, now) }
            try {
                body.readBytes()
                Thread.sleep(delayMillis)
                if (failFirst && attempts.getAndIncrement() == 0) {
                    callback.onFailure(UploadErrorCode.NETWORK_ERROR)
                } else {
                    callback.onSuccess(
                        ImageUploadResponse(
                            clientId = clientId,
                            uploadId = "upload-$clientId",
                            url = "https://r2.2libra.com/i/$clientId.png",
                            mimeType = "image/png",
                            bytes = image.bytes
                        )
                    )
                }
            } finally {
                active.decrementAndGet()
            }
            return UploadCall { }
        }
    }
}
