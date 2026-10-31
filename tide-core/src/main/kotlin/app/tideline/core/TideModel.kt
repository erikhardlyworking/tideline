package app.tideline.core

import kotlin.math.*

data class SupportCell(val row: Int, val col: Int, val point: GeoPoint, val distanceKm: Double, val weight: Double)
data class ModelLocation(val requested: GeoPoint, val cells: List<SupportCell>, val method: String, val shoreSnapKm: Double, val seaPoint: GeoPoint = requested) {
    val distanceKm get() = cells.maxOf { it.distanceKm }
    val nearestCellDistanceKm get() = cells.minOf { it.distanceKm }
    val represented get() = cells.minBy { it.distanceKm }.point
}

sealed class LocationResult {
    data class Available(val location: ModelLocation) : LocationResult()
    data class Unavailable(val reason: String, val problem: LocationProblem = LocationProblem.NO_COVERAGE, val nearestCellDistanceKm: Double? = null) : LocationResult()
}
enum class LocationProblem { TOO_FAR_FROM_COAST, NO_COVERAGE }

class TideModel(val pack: ModelPack) {
    fun locate(point: GeoPoint, shoreLimitKm: Double = 30.0): LocationResult {
        require(shoreLimitKm in 1.0..30.0)
        val nearestStored = pack.nearestCell(point) ?: return LocationResult.Unavailable("No tide data available.")
        if (nearestStored.distanceKm > 30.0)
            return LocationResult.Unavailable("Too far from coast", LocationProblem.TOO_FAR_FROM_COAST, nearestStored.distanceKm)
        val sea = pack.water.nearbySea(point, shoreLimitKm)
            ?: return LocationResult.Unavailable("No supported coast at this location.", nearestCellDistanceKm = nearestStored.distanceKm)
        val snap = point.distanceKm(sea)
        val latitudeIndex = floor((point.latitude - pack.latitude0) / pack.step).toInt().coerceIn(0, pack.rows - 2)
        val longitude = ((point.longitude - pack.longitude0) % 360 + 360) % 360
        val longitudeIndex = floor(longitude / pack.step).toInt()
        val fx = longitude / pack.step - longitudeIndex
        val fy = (point.latitude - pack.latitude0) / pack.step - latitudeIndex
        val corners = listOf(latitudeIndex to longitudeIndex, latitudeIndex to longitudeIndex + 1, latitudeIndex + 1 to longitudeIndex, latitudeIndex + 1 to longitudeIndex + 1)
        val weights = listOf((1 - fy) * (1 - fx), (1 - fy) * fx, fy * (1 - fx), fy * fx)
        if (corners.all { (r, c) -> point.distanceKm(pack.point(r, c)) <= 30.0 && pack.cell(r, c) != null && pack.water.clearPath(sea, pack.point(r, c)) }) {
            // Also test diagonals so an island between four wet corners is not blended across.
            if (pack.water.clearPath(pack.point(corners[0].first, corners[0].second), pack.point(corners[3].first, corners[3].second)) &&
                pack.water.clearPath(pack.point(corners[1].first, corners[1].second), pack.point(corners[2].first, corners[2].second))) {
                return LocationResult.Available(ModelLocation(point, corners.mapIndexed { i, (r, c) ->
                    val p = pack.point(r, c); SupportCell(r, Math.floorMod(c, pack.cols), p, point.distanceKm(p), weights[i])
                }, "Complex bilinear", snap, sea))
            }
        }
        // Search geographic distance, not degree distance, with periodic longitude.
        val longitudeReach = ceil(30.0 / (111.0 * pack.step * cos(Math.toRadians(point.latitude)).coerceAtLeast(1e-6))).toInt().coerceAtMost(pack.cols / 2)
        val latitudeReach = ceil(30.0 / (111.0 * pack.step)).toInt() + 1
        val candidates = mutableListOf<SupportCell>()
        for (r in max(0, latitudeIndex - latitudeReach)..min(pack.rows - 1, latitudeIndex + latitudeReach)) {
            for (dc in -longitudeReach..longitudeReach) {
                val c = Math.floorMod(longitudeIndex + dc, pack.cols)
                val p = pack.point(r, c); val distance = point.distanceKm(p)
                if (distance <= 30 && pack.cell(r, c) != null) candidates += SupportCell(r, c, p, distance, 1.0)
            }
        }
        val nearest = candidates.sortedWith(compareBy<SupportCell> { it.distanceKm }.thenBy { it.row }.thenBy { it.col })
            .firstOrNull { pack.water.clearPath(sea, it.point) }
            ?: return LocationResult.Unavailable("No supported sea cell reachable within 30 km.", nearestCellDistanceKm = nearestStored.distanceKm)
        return LocationResult.Available(ModelLocation(point, listOf(nearest), "Nearest sea cell", snap, sea))
    }

    fun signal(location: ModelLocation): TideSignal = signal(location.cells)
    fun signal(cells: List<SupportCell>): TideSignal {
        require(cells.isNotEmpty() && abs(cells.sumOf { it.weight } - 1.0) < 1e-8)
        val values = Array(17) { Complex(0.0, 0.0) }
        for (cell in cells) {
            require(cell.weight >= 0)
            val harmonics = pack.cell(cell.row, cell.col) ?: error("Missing harmonic data")
            for (i in values.indices) values[i] = values[i] + harmonics[i] * cell.weight
        }
        return pack.astronomy.signal(values)
    }
}
