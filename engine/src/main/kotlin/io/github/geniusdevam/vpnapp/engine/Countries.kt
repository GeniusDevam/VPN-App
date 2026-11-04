package io.github.geniusdevam.vpnapp.engine

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Countries {
    /** Regional-indicator flag emoji built from the ISO code; no image assets needed. */
    fun flag(iso: String): String {
        if (iso.length != 2) return "🌐"
        val a = Character.toChars(0x1F1E6 + (iso[0].uppercaseChar() - 'A'))
        val b = Character.toChars(0x1F1E6 + (iso[1].uppercaseChar() - 'A'))
        return String(a) + String(b)
    }

    fun name(iso: String, locale: Locale = Locale.getDefault(), fallback: String? = null): String {
        val n = Locale("", iso.uppercase()).getDisplayCountry(locale)
        return if (n.isNullOrBlank() || n.equals(iso, ignoreCase = true)) (fallback ?: iso.uppercase()) else n
    }

    // Approximate centroids (lat, lon): only used to rank candidates before measuring.
    private val centroids: Map<String, Pair<Double, Double>> = mapOf(
        "JP" to (36.0 to 138.0), "KR" to (36.5 to 127.8), "US" to (39.8 to -98.6), "RU" to (61.5 to 99.0),
        "TH" to (15.0 to 101.0), "VN" to (16.0 to 108.0), "TW" to (23.7 to 121.0), "IN" to (21.0 to 78.0),
        "ID" to (-2.5 to 118.0), "DE" to (51.0 to 10.4), "FR" to (46.6 to 2.4), "GB" to (54.0 to -2.5),
        "CA" to (56.0 to -106.0), "AU" to (-25.0 to 134.0), "BR" to (-10.0 to -52.0), "NL" to (52.2 to 5.3),
        "IT" to (42.8 to 12.5), "ES" to (40.2 to -3.6), "PL" to (52.0 to 19.4), "UA" to (49.0 to 31.4),
        "TR" to (39.0 to 35.0), "SG" to (1.35 to 103.8), "HK" to (22.3 to 114.2), "CN" to (35.0 to 103.0),
        "MX" to (23.6 to -102.5), "AR" to (-34.0 to -64.0), "CL" to (-30.0 to -71.0), "ZA" to (-29.0 to 24.0),
        "SE" to (62.0 to 15.0), "NO" to (64.5 to 11.5), "FI" to (64.0 to 26.0), "DK" to (56.0 to 10.0),
        "CH" to (46.8 to 8.2), "AT" to (47.5 to 14.5), "BE" to (50.6 to 4.7), "CZ" to (49.8 to 15.5),
        "RO" to (45.9 to 25.0), "HU" to (47.2 to 19.5), "GR" to (39.0 to 22.0), "PT" to (39.5 to -8.0),
        "IE" to (53.2 to -8.2), "IL" to (31.5 to 34.9), "AE" to (24.0 to 54.0), "SA" to (24.0 to 45.0),
        "EG" to (26.8 to 30.8), "NG" to (9.1 to 8.7), "KE" to (0.0 to 38.0), "PK" to (30.0 to 70.0),
        "BD" to (24.0 to 90.0), "MY" to (4.2 to 102.0), "PH" to (12.8 to 122.0), "NZ" to (-41.0 to 174.0),
        "CO" to (4.0 to -72.0), "PE" to (-10.0 to -76.0), "VE" to (7.0 to -66.0), "BG" to (42.7 to 25.5),
        "RS" to (44.0 to 21.0), "SK" to (48.7 to 19.5), "LT" to (55.3 to 23.9), "LV" to (56.9 to 24.6),
        "EE" to (58.6 to 25.0), "BY" to (53.7 to 27.9), "KZ" to (48.0 to 68.0), "IR" to (32.0 to 53.0),
        "IQ" to (33.0 to 44.0), "LK" to (7.9 to 80.7), "NP" to (28.4 to 84.1), "MM" to (21.9 to 96.0),
        "KH" to (12.5 to 105.0), "LA" to (19.9 to 102.5), "MN" to (46.9 to 103.8), "MD" to (47.4 to 28.4),
        "HR" to (45.1 to 15.2), "SI" to (46.1 to 14.8), "LU" to (49.8 to 6.1), "IS" to (65.0 to -18.0),
    )

    fun distanceKm(a: String?, b: String?): Double? {
        val ca = centroids[a?.uppercase() ?: return null] ?: return null
        val cb = centroids[b?.uppercase() ?: return null] ?: return null
        val r = 6371.0
        val dLat = Math.toRadians(cb.first - ca.first)
        val dLon = Math.toRadians(cb.second - ca.second)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(ca.first)) * cos(Math.toRadians(cb.first)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }
}

/** A country that currently has servers, for the picker. */
data class CountryOption(val iso: String, val name: String, val serverCount: Int)

fun List<Server>.countryOptions(locale: Locale = Locale.getDefault()): List<CountryOption> =
    groupBy { it.countryCode }
        .map { (iso, list) -> CountryOption(iso, Countries.name(iso, locale, list.first().countryName), list.size) }
        .sortedBy { it.name.lowercase() }
