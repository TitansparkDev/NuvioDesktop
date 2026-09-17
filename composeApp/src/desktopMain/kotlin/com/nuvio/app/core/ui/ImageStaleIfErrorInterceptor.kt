package com.nuvio.app.core.ui

import coil3.Uri
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.CancellationException

/**
 * Serves the stale copy of an image whose refresh failed for a reason that may not last.
 *
 * Before the disk cache honoured `Cache-Control`, nothing on disk ever expired, so a poster
 * service being down cost nothing: the old poster kept showing. Now that a provider can say
 * "ask me again in a day", a day later every one of its posters is refetched — and if the
 * container is restarting or the machine's Wi-Fi has dropped, Coil's `NetworkFetcher` throws on
 * the failed request without ever looking back at the snapshot it has open. A row of blank cards
 * because a badge *might* have changed is the wrong trade, and one every HTTP cache makes the
 * other way (`stale-if-error`, RFC 5861).
 *
 * So on a transient failure the request is run once more with network reads disabled. Coil then
 * sends `only-if-cached`, `ImageDiskCacheStrategy` serves whatever is on disk regardless of age,
 * and the copy is booked as fresh for a short grace period so the next few loads come straight
 * from memory instead of failing the same way first. A permanent failure — a 404 because the
 * title is gone — is returned as is: that answer is the poster no longer existing, and the
 * artwork chain should fall back.
 */
internal class ImageStaleIfErrorInterceptor(
    private val gracePeriodMillis: Long = DefaultGracePeriodMillis,
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        // Depending on the engine path, failures surface either as a thrown exception or as an
        // ErrorResult — handle both, as TransientImageErrorRetryInterceptor does, and hand the
        // failure back in whichever form it took if there is no stale copy to offer instead.
        val outcome: Any = try {
            chain.proceed()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error
        }
        val failure = when (outcome) {
            is ErrorResult -> outcome.throwable
            is Throwable -> outcome
            else -> return outcome as ImageResult
        }

        staleCopyOrNull(chain, failure)?.let { return it }
        if (outcome is Throwable) throw outcome
        return outcome as ImageResult
    }

    private suspend fun staleCopyOrNull(chain: Interceptor.Chain, failure: Throwable): ImageResult? {
        val request = chain.request
        if (!request.networkCachePolicy.readEnabled || !request.diskCachePolicy.readEnabled) {
            // Already a cache-only request, or one that could never have a disk copy.
            return null
        }
        if (!isTransientImageError(failure)) return null

        // Only keys the strategy has seen this session have a disk copy worth asking for: an
        // unknown one would make Coil run the network request a second time, with no snapshot
        // to fall back on.
        val diskCacheKey = request.diskCacheKey ?: when (val data = request.data) {
            is String -> data
            is Uri -> data.toString()
            else -> null
        }
        if (diskCacheKey == null || ImageFreshnessRegistry.get(diskCacheKey) == null) return null

        val stale = try {
            chain.withRequest(
                request.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build(),
            ).proceed()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return null
        }
        if (stale !is SuccessResult) return null

        ImageFreshnessRegistry.record(
            diskCacheKey,
            expiresAtMillis = ImageFreshnessRegistry.nowMillis() + gracePeriodMillis,
            bodyChanged = false,
        )
        return stale
    }

    private companion object {
        /**
         * Long enough that a poster service restart or a Wi-Fi blip is not paid for once per
         * card per scroll; short enough that the badge the refresh was for is not late by much.
         */
        const val DefaultGracePeriodMillis = 5L * 60 * 1000
    }
}
