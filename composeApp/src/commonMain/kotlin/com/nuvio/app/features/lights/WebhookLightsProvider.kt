package com.nuvio.app.features.lights

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The catch-all: one URL per event, for Home Assistant, Hubitat, IFTTT, a Node-RED flow — anything
 * that can turn a request into a scene. Nuvio does not know or care what is on the other end; a
 * POST carries a small JSON body naming the event so one endpoint can serve all four.
 */
internal object WebhookLightsProvider : LightsProvider {
    override val id: String = "webhook"

    private val log = Logger.withTag("Lights/Webhook")
    private const val CALL_TIMEOUT_MS = 5_000L
    private const val CONNECT_TIMEOUT_MS = 3_000L

    override fun isConfigured(settings: LightsSettings): Boolean = settings.hasWebhook

    override suspend fun handle(event: LightsEvent, settings: LightsSettings) {
        val url = when (event) {
            LightsEvent.Start -> settings.webhookStartUrl
            LightsEvent.Pause -> settings.webhookPauseUrl
            LightsEvent.Resume -> settings.webhookResumeUrl
            LightsEvent.End -> settings.webhookEndUrl
        }.takeIf { it.isNotBlank() } ?: return

        val isPost = settings.webhookMethod == LightsWebhookMethod.Post
        val body = if (isPost) {
            buildJsonObject {
                put("source", "nuvio")
                put("event", event.name.lowercase())
            }.toString()
        } else {
            ""
        }
        val response = runCatching {
            httpRequestRaw(
                method = if (isPost) "POST" else "GET",
                url = url,
                headers = if (isPost) mapOf("Content-Type" to "application/json") else emptyMap(),
                body = body,
                callTimeoutMs = CALL_TIMEOUT_MS,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
            )
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()

        when {
            response == null -> log.w { "${event.name} webhook unreachable: $url" }
            response.status !in 200..299 -> log.w { "${event.name} webhook → HTTP ${response.status}: $url" }
            else -> log.i { "${event.name} webhook delivered" }
        }
    }
}
