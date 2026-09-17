package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.isExplicitProviderDiagnosticVideoUrl
import com.nuvio.app.features.player.playbackSourceFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Where mpv should be pointed for a stream URL, after following the addon's redirect chain once
 * on the client.
 *
 * Why this exists: FFmpeg's http protocol reverts to the *original* URL on every seek and every
 * `reconnect=1` reconnect (`http_seek_internal`, "if the location changed (redirect), revert to
 * the original uri"), and it only remembers a 3xx hop when that response carried a cache
 * lifetime (`Cache-Control: max-age`/`s-maxage` or `Expires`). A debrid resolver that answers
 * `307` + `Cache-Control: no-store` (AIOStreams' `/api/v1/debrid/playback/`) therefore gets a
 * fresh resolve — a fresh debrid API call — for every seek outside mpv's cache, which is how a
 * scrub session earns the provider's 429. Handing mpv the redirect target instead collapses that
 * to one resolve per play.
 *
 * [playbackUrl] differs from [sourceUrl] only when pinning is worthwhile; the source URL stays
 * the stream's identity everywhere else (link cache, failover keys, watch progress).
 */
internal data class PlaybackRedirectResolution(
    val sourceUrl: String,
    val playbackUrl: String,
    val hops: Int,
    val outcome: Outcome,
) {
    enum class Outcome {
        /** Not an http(s) URL, or a host known to serve media directly — nothing to resolve. */
        Skipped,
        /** The URL answered without redirecting; mpv opens it as-is. */
        NoRedirect,
        /** Redirected, and FFmpeg would not remember the hop: mpv gets the final target. */
        Pinned,
        /** Redirected with a cache lifetime: FFmpeg already goes direct and self-refreshes. */
        CacheableRedirect,
        /** Redirected to a provider status/placeholder video; left to the diagnostic paths. */
        Placeholder,
        /** Network/protocol failure while probing; mpv opens the source URL as before. */
        Failed,
        /** An earlier probe of this endpoint settled the question; nothing was requested. */
        Remembered,
    }

    val pinned: Boolean get() = playbackUrl != sourceUrl

    companion object {
        fun unchanged(sourceUrl: String, outcome: Outcome, hops: Int = 0) =
            PlaybackRedirectResolution(sourceUrl, sourceUrl, hops, outcome)
    }
}

internal object PlaybackRedirectResolver {
    private val log = Logger.withTag("PlaybackRedirect")

    /** Same-host bounces followed before giving up (AIOStreams 307s onto its own usenet endpoint). */
    private const val MAX_SAME_HOST_HOPS = 4

    /**
     * Hosts that hand out the bytes themselves. Probing one of these is an extra connection open
     * against exactly the host whose open-rate throttle we are trying to stay under, so they are
     * never probed. Misses only cost that one extra open; this list is not load-bearing.
     */
    private val DIRECT_MEDIA_HOST_SUFFIXES = listOf(
        "tb-cdn.st",
        "real-debrid.com",
        "rdeb.io",
        "debrid.it",
        "energycdn.com",
        "premiumize.me",
    )

    /**
     * The whole probe sits in series with playback start, so it gets a tight budget: a resolver
     * that is stalling (Debridio waiting on a throttled TorBox call cost 14 s once) should cost
     * a few seconds at most, and only once — see [endpointVerdicts].
     */
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(4)

    /** How long a failed probe keeps its endpoint off the probe list. */
    private const val FAILED_VERDICT_TTL_MS = 5L * 60L * 1000L

    /**
     * How long a remembered NoRedirect/CacheableRedirect verdict survives on disk. Such a verdict
     * describes the resolver's behaviour, which changes with its deployment, not per link — so it
     * is worth keeping across launches: every probe of a cacheable resolver is one *extra* debrid
     * API call (the resolver resolves for the probe, then again for mpv), which is a real cost on
     * a provider whose API is the thing being rate-limited.
     */
    private const val PERSISTED_VERDICT_TTL_MS = 7L * 24L * 60L * 60L * 1000L

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private class EndpointVerdict(val outcome: PlaybackRedirectResolution.Outcome, val atMs: Long)

    /**
     * What an endpoint (host + first three path segments, e.g. `addon.debridio.com/play/series/torbox`
     * or `aio.example/api/v1/debrid`) did the last time it was probed. Only [Outcome.Pinned]
     * endpoints are worth asking again — every other verdict is a property of the endpoint, not
     * the link, so the probe would just be an extra round trip before every play.
     */
    private val endpointVerdicts = ConcurrentHashMap<String, EndpointVerdict>()
    private val REMEMBERED_OUTCOMES = setOf(
        PlaybackRedirectResolution.Outcome.NoRedirect,
        PlaybackRedirectResolution.Outcome.CacheableRedirect,
        PlaybackRedirectResolution.Outcome.Failed,
    )
    private val PERSISTED_OUTCOMES = setOf(
        PlaybackRedirectResolution.Outcome.NoRedirect,
        PlaybackRedirectResolution.Outcome.CacheableRedirect,
    )
    private val verdictStore by lazy { DesktopStorage.store("nuvio_playback_redirect_verdicts") }

    private fun rememberedVerdict(endpoint: String): EndpointVerdict? {
        endpointVerdicts[endpoint]?.let { return it }
        val persisted = runCatching { verdictStore.getString(endpoint) }.getOrNull() ?: return null
        val (name, at) = persisted.split('|', limit = 2).takeIf { it.size == 2 } ?: return null
        val outcome = PlaybackRedirectResolution.Outcome.entries.firstOrNull { it.name == name }
            ?: return null
        val atMs = at.toLongOrNull() ?: return null
        if (System.currentTimeMillis() - atMs > PERSISTED_VERDICT_TTL_MS) {
            runCatching { verdictStore.remove(endpoint) }
            return null
        }
        return EndpointVerdict(outcome, atMs).also { endpointVerdicts[endpoint] = it }
    }

    private fun rememberVerdict(endpoint: String, outcome: PlaybackRedirectResolution.Outcome) {
        val verdict = EndpointVerdict(outcome, System.currentTimeMillis())
        endpointVerdicts[endpoint] = verdict
        if (outcome in PERSISTED_OUTCOMES) {
            runCatching { verdictStore.putString(endpoint, "${outcome.name}|${verdict.atMs}") }
        }
    }

    suspend fun resolve(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
    ): PlaybackRedirectResolution = withContext(Dispatchers.IO) {
        val normalized = sourceUrl.trim()
        val isHttp = normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true)
        if (!isHttp || isDirectMediaHost(normalized)) {
            return@withContext PlaybackRedirectResolution.unchanged(
                normalized,
                PlaybackRedirectResolution.Outcome.Skipped,
            )
        }
        val endpoint = endpointKey(normalized)
        rememberedVerdict(endpoint)?.let { verdict ->
            val expired = verdict.outcome == PlaybackRedirectResolution.Outcome.Failed &&
                System.currentTimeMillis() - verdict.atMs > FAILED_VERDICT_TTL_MS
            if (expired) {
                endpointVerdicts.remove(endpoint)
            } else {
                return@withContext PlaybackRedirectResolution.unchanged(
                    normalized,
                    PlaybackRedirectResolution.Outcome.Remembered,
                )
            }
        }

        val resolution = probeChain(normalized, sourceHeaders)
        // Pinned and Placeholder are verdicts on the link (the next one from the same resolver
        // may pin fine); the rest describe the endpoint itself and end the probing.
        if (resolution.outcome in REMEMBERED_OUTCOMES) rememberVerdict(endpoint, resolution.outcome)
        resolution
    }

    private fun probeChain(
        normalized: String,
        sourceHeaders: Map<String, String>,
    ): PlaybackRedirectResolution {
        var current = normalized
        var hops = 0
        var everyRedirectCacheable = true
        while (true) {
            val response = runCatching { probe(current, sourceHeaders) }.getOrElse { error ->
                log.w { "probe failed hop=$hops host=${hostOf(current)}: ${error.message}" }
                return PlaybackRedirectResolution.unchanged(
                    normalized,
                    PlaybackRedirectResolution.Outcome.Failed,
                    hops,
                )
            }
            val location = response.location
            if (response.statusCode !in 300..399 || location == null) break

            hops += 1
            everyRedirectCacheable = everyRedirectCacheable &&
                ffmpegWouldCacheRedirect(response.cacheControl, response.expires)
            val next = runCatching { URI(current).resolve(location).toString() }.getOrNull()
                ?: return PlaybackRedirectResolution.unchanged(
                    normalized,
                    PlaybackRedirectResolution.Outcome.Failed,
                    hops,
                )
            current = next
            // A cross-host hop is the CDN (or another resolver); don't touch it — that open is
            // mpv's to make. Same-host bounces are the resolver rearranging its own endpoints.
            if (!hostOf(next).equals(hostOf(response.requestUrl), ignoreCase = true)) break
            if (hops >= MAX_SAME_HOST_HOPS) break
        }

        val outcome = when {
            hops == 0 -> PlaybackRedirectResolution.Outcome.NoRedirect
            isExplicitProviderDiagnosticVideoUrl(current) ||
                playbackSourceFailure(current) != null ||
                isProviderStatusClipUrl(current) ->
                PlaybackRedirectResolution.Outcome.Placeholder
            everyRedirectCacheable -> PlaybackRedirectResolution.Outcome.CacheableRedirect
            else -> PlaybackRedirectResolution.Outcome.Pinned
        }
        val playbackUrl = if (outcome == PlaybackRedirectResolution.Outcome.Pinned) current else normalized
        return PlaybackRedirectResolution(normalized, playbackUrl, hops, outcome)
    }

    /**
     * AIOStreams answers a usenet resolve that went wrong with a 307 onto its own
     * `…/download_failed.mp4` (and siblings) — a real 120 s clip the diagnostic path already
     * recognises by duration. It must not be pinned: the source URL is what the retry re-resolves.
     */
    internal fun isProviderStatusClipUrl(url: String): Boolean {
        val file = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
            .substringAfterLast('/')
            .lowercase(Locale.ROOT)
        return file.endsWith(".mp4") &&
            (file.contains("failed") || file.contains("error") || file.contains("status"))
    }

    /** Host plus up to three leading path segments; the resolver, not the specific link. */
    internal fun endpointKey(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }.take(3)
        return (uri.host.orEmpty().lowercase(Locale.ROOT) + "/" + segments.joinToString("/"))
    }

    private class ProbeResponse(
        val requestUrl: String,
        val statusCode: Int,
        val location: String?,
        val cacheControl: String?,
        val expires: String?,
    )

    /**
     * One request, body never read. `Range: bytes=0-0` keeps a resolver that answers 200 with the
     * media itself down to a byte; closing the stream before EOF drops the connection for anyone
     * who ignores Range.
     */
    private fun probe(url: String, sourceHeaders: Map<String, String>): ProbeResponse {
        val builder = HttpRequest.newBuilder()
            .uri(URI(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Range", "bytes=0-0")
            .GET()
        var hasUserAgent = false
        sourceHeaders.forEach { (key, value) ->
            val cleanKey = key.trim()
            val cleanValue = value.trim()
            if (cleanKey.isBlank() || cleanValue.isBlank() ||
                cleanKey.equals("Range", ignoreCase = true) ||
                isRestrictedJdkHeader(cleanKey)
            ) {
                return@forEach
            }
            if (cleanKey.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            builder.header(cleanKey, cleanValue)
        }
        // Match what mpv will send so a resolver that keys on the UA sees one client, not two.
        if (!hasUserAgent) builder.header("User-Agent", DESKTOP_PLAYBACK_FALLBACK_USER_AGENT)

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        runCatching { response.body().close() }
        val headers = response.headers()
        return ProbeResponse(
            requestUrl = url,
            statusCode = response.statusCode(),
            location = headers.firstValue("Location").orElse(null),
            cacheControl = headers.firstValue("Cache-Control").orElse(null),
            expires = headers.firstValue("Expires").orElse(null),
        )
    }

    /**
     * Mirrors libavformat/http.c `parse_expires` + `parse_cache_control`: `Expires` wins when
     * present, `no-cache`/`no-store` disable caching, else `s-maxage` then `max-age`. Anything
     * FFmpeg would not remember is a redirect it will re-request on every seek.
     */
    internal fun ffmpegWouldCacheRedirect(
        cacheControlHeader: String?,
        expiresHeader: String?,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): Boolean {
        expiresHeader?.let { expires ->
            val parsed = runCatching {
                ZonedDateTime.parse(expires.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
            }.getOrNull()
            if (parsed != null) return parsed.isAfter(now)
        }
        val cacheControl = cacheControlHeader?.lowercase(Locale.ROOT) ?: return false
        if ("no-cache" in cacheControl || "no-store" in cacheControl) return false
        val age = Regex("""s-maxage=(\d+)""").find(cacheControl)?.groupValues?.get(1)
            ?: Regex("""max-age=(\d+)""").find(cacheControl)?.groupValues?.get(1)
        return (age?.toLongOrNull() ?: 0L) > 0L
    }

    internal fun isDirectMediaHost(url: String): Boolean {
        val host = hostOf(url).lowercase(Locale.ROOT)
        return DIRECT_MEDIA_HOST_SUFFIXES.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }

    internal fun hostOf(url: String): String =
        runCatching { URI(url).host.orEmpty() }.getOrDefault("")

    private fun isRestrictedJdkHeader(name: String): Boolean =
        name.equals("Connection", ignoreCase = true) ||
            name.equals("Content-Length", ignoreCase = true) ||
            name.equals("Expect", ignoreCase = true) ||
            name.equals("Host", ignoreCase = true) ||
            name.equals("Upgrade", ignoreCase = true)
}
