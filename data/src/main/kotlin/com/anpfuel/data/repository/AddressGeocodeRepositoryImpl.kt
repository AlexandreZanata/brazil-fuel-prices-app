package com.anpfuel.data.repository

import com.anpfuel.data.local.preferences.AddressGeocodeCacheStore
import com.anpfuel.data.remote.AddressGeocodeCacheKeyFormatter
import com.anpfuel.data.remote.NominatimRateLimiter
import com.anpfuel.data.remote.NominatimSearchClient
import com.anpfuel.domain.repository.AddressGeocodeOutcome
import com.anpfuel.domain.repository.AddressGeocodeRepository
import com.anpfuel.domain.rule.NominatimRateLimitRule
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * BR-021 — forward geocoding with local cache and a 1 req/s Nominatim throttle.
 *
 * Unlike the reverse geocoding flow, a saturated throttle is awaited (bounded) instead of
 * failing immediately, so UC-015 can resolve a handful of candidates in one user request.
 */
@Singleton
class AddressGeocodeRepositoryImpl @Inject constructor(
    private val addressGeocodeCacheStore: AddressGeocodeCacheStore,
    private val nominatimSearchClient: NominatimSearchClient,
    private val rateLimiter: NominatimRateLimiter,
) : AddressGeocodeRepository {

    private val requestMutex = Mutex()

    override suspend fun geocode(query: String): AddressGeocodeOutcome {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return AddressGeocodeOutcome.NotFound
        }

        val cacheKey = AddressGeocodeCacheKeyFormatter.format(trimmedQuery)
        addressGeocodeCacheStore.get(cacheKey)?.let { cached ->
            return AddressGeocodeOutcome.Success(cached)
        }

        return requestMutex.withLock {
            addressGeocodeCacheStore.get(cacheKey)?.let { cached ->
                return@withLock AddressGeocodeOutcome.Success(cached)
            }

            if (!awaitRateLimitSlot()) {
                return@withLock AddressGeocodeOutcome.RateLimited
            }

            val coordinates = try {
                rateLimiter.recordRequest()
                nominatimSearchClient.search(trimmedQuery)
            } catch (_: IOException) {
                return@withLock AddressGeocodeOutcome.NetworkError
            } ?: return@withLock AddressGeocodeOutcome.NotFound

            addressGeocodeCacheStore.put(cacheKey, coordinates)
            AddressGeocodeOutcome.Success(coordinates)
        }
    }

    /**
     * Waits for the next Nominatim slot, up to [MAX_RATE_LIMIT_WAIT_MILLIS] (BR-021).
     *
     * @return `true` when a request may be sent.
     */
    private suspend fun awaitRateLimitSlot(): Boolean {
        var waitedMillis = 0L
        while (!rateLimiter.canRequest()) {
            if (waitedMillis >= MAX_RATE_LIMIT_WAIT_MILLIS) {
                return false
            }
            delay(RATE_LIMIT_POLL_MILLIS)
            waitedMillis += RATE_LIMIT_POLL_MILLIS
        }
        return true
    }

    companion object {
        internal const val RATE_LIMIT_POLL_MILLIS = 100L
        internal val MAX_RATE_LIMIT_WAIT_MILLIS = NominatimRateLimitRule.MIN_INTERVAL_MILLIS * 10
    }
}
