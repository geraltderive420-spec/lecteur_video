package com.lecteur.core.network.tmdb

import okhttp3.Interceptor
import okhttp3.Response

class TmdbAuthInterceptor(private val apiKey: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.newBuilder().addQueryParameter("api_key", apiKey).build()
        return chain.proceed(request.newBuilder().url(url).build())
    }
}

/**
 * Spaces requests out through [RequestPacer] and retries what TMDB asks to retry: 429 honours `Retry-After`,
 * 5xx get a short back-off. Network failures are not retried here: they mean "offline" and the work queue
 * (WorkManager) decides when to try again.
 */
class TmdbRateLimitInterceptor(
    private val pacer: RequestPacer = RequestPacer(),
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val maxRetries: Int = 3
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        while (true) {
            val wait = pacer.reserve()
            if (wait > 0) sleep(wait)

            val response = chain.proceed(chain.request())
            val delay = retryDelayMs(response.code, response.header("Retry-After"), attempt)
            if (delay == null || attempt >= maxRetries) return response

            response.close()
            sleep(delay)
            attempt++
        }
    }

    companion object {
        private const val DEFAULT_RETRY_AFTER_MS = 2_000L
        private const val MAX_RETRY_AFTER_MS = 10_000L

        /** Milliseconds to wait before retrying, or null when the response is final. */
        fun retryDelayMs(code: Int, retryAfterHeader: String?, attempt: Int): Long? = when {
            code == 429 -> ((retryAfterHeader?.trim()?.toLongOrNull()?.times(1000)) ?: DEFAULT_RETRY_AFTER_MS)
                .coerceIn(500, MAX_RETRY_AFTER_MS)
            code in 500..599 -> 500L * (attempt + 1)
            else -> null
        }
    }
}
