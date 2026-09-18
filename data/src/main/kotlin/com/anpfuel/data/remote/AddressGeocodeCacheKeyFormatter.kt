package com.anpfuel.data.remote

/**
 * Normalizes a geocoding query into a DataStore-safe cache key so that
 * repeated requests for the same address never hit Nominatim twice (BR-021).
 */
object AddressGeocodeCacheKeyFormatter {

    private val WHITESPACE_REGEX = Regex("\\s+")

    fun format(query: String): String =
        query.trim()
            .lowercase()
            .replace(WHITESPACE_REGEX, " ")
}
