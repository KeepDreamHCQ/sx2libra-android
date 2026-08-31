package com.suixin.sx2libra.web

import com.suixin.sx2libra.model.ImageUploadEvent
import org.json.JSONObject

/** Encodes value-only upload events for a page-owned reply/event channel. */
object BridgeEventEncoder {
    fun encode(event: ImageUploadEvent): String {
        val root = JSONObject()
            .put("version", BridgeProtocol.VERSION)
            .put("requestId", event.requestId)
        when (event) {
            is ImageUploadEvent.Selected -> root
                .put("event", "image_upload_selected")
                .put("payload", JSONObject()
                    .put("clientId", event.clientId)
                    .put("selectionIndex", event.selectionIndex)
                    .put("displayName", event.displayName))
            is ImageUploadEvent.Queued -> root
                .put("event", "image_upload_queued")
                .put("payload", JSONObject().put("clientId", event.clientId))
            is ImageUploadEvent.Started -> root
                .put("event", "image_upload_started")
                .put("payload", JSONObject().put("clientId", event.clientId))
            is ImageUploadEvent.Progressed -> root
                .put("event", "image_upload_progress")
                .put("payload", JSONObject()
                    .put("clientId", event.clientId)
                    .put("completedBytes", event.progress.completedBytes)
                    .put("totalBytes", event.progress.totalBytes)
                    .put("fraction", event.progress.fraction.toDouble()))
            is ImageUploadEvent.Completed -> root
                .put("event", "image_upload_completed")
                .put("payload", JSONObject()
                    .put("clientId", event.clientId)
                    .put("markdown", event.markdown))
            is ImageUploadEvent.Failed -> root
                .put("event", "image_upload_failed")
                .put("payload", JSONObject()
                    .put("clientId", event.clientId)
                    .put("error", event.error.name)
                    .put("retryable", event.retryable))
            is ImageUploadEvent.Cancelled -> root
                .put("event", "image_upload_cancelled")
                .put("payload", JSONObject().put("clientId", event.clientId))
            is ImageUploadEvent.BatchFinished -> root
                .put("event", "image_upload_batch_finished")
                .put("payload", JSONObject()
                    .put("successfulClientIds", event.successfulClientIds)
                    .put("failedClientIds", event.failedClientIds))
            is ImageUploadEvent.BatchCancelled -> root
                .put("event", "image_upload_batch_cancelled")
                .put("payload", JSONObject())
        }
        return root.toString()
    }
}
