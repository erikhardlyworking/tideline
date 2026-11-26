package app.tideline

import android.content.Context
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Bundled GeoNames subset. Loading/searching runs on the activity's worker. */
object CoastalPlaces {
    data class Place(val id: Long, val name: String, val country: String, val region: String,
                     val latitude: Double, val longitude: Double, val aliases: String) {
        val key = normalized(name)
        val searchNames = (listOf(name) + aliases.split(',')).map(::normalized).distinct()
        fun description(): String = listOf(region, Locale("", country).displayCountry).filter { it.isNotBlank() }.distinct().joinToString(", ")
    }
    @Volatile private var cached: List<Place>? = null
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()
    @Synchronized private fun all(context: Context): List<Place> = cached ?: GZIPInputStream(context.applicationContext.assets.open("coastal-places.idx"))
        .bufferedReader().useLines { lines -> lines.map { line ->
            val f = line.split('\t')
            Place(f[0].toLong(), f[1], f[2], f[3], f[4].toDouble(), f[5].toDouble(), f.getOrElse(6) { "" })
        }.toList() }.also { cached = it }

    fun search(context: Context, query: String, limit: Int = 20): List<Place> {
        val q = normalized(query).take(80)
        if (q.length < 2) return emptyList()
        // Input is literal, never a regex. The source is ordered by population;
        // exact/prefix matches outrank fragments while preserving that order.
        return all(context).asSequence().mapNotNull { place ->
            val score = when {
                place.key == q -> 0
                place.key.startsWith(q) -> 1
                q in place.searchNames -> 2
                place.searchNames.any { it.startsWith(q) } -> 3
                place.searchNames.any { q in it } -> 4
                else -> return@mapNotNull null
            }
            score to place
        }.sortedBy { it.first }.take(limit.coerceIn(1, 50)).map { it.second }.toList()
    }
}
