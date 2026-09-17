package com.anpfuel.domain.repository

import com.anpfuel.domain.valueobject.GeoCoordinates

sealed interface AddressGeocodeOutcome {
    data class Success(val coordinates: GeoCoordinates) : AddressGeocodeOutcome

    /**
     * Nominatim returned no usable hit for the query, including malformed payloads.
     */
    data object NotFound : AddressGeocodeOutcome

    data object NetworkError : AddressGeocodeOutcome

    /**
     * A Nominatim rate-limit slot (BR-021 — max 1 request per second) could not be acquired in time.
     */
    data object RateLimited : AddressGeocodeOutcome
}

/**
 * Port for forward geocoding (normalized address → coordinates) with client-side cache (UC-015, BR-021).
 */
interface AddressGeocodeRepository {

    suspend fun geocode(query: String): AddressGeocodeOutcome
}
