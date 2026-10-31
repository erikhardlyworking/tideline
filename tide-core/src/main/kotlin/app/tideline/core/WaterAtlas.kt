package app.tideline.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Tiled Natural Earth marine polygons, with original double-precision vertices. */
class WaterAtlas(private val pack: ModelPack) {
    private data class Point(val x: Double, val y: Double)
    private data class Polygon(val rings: List<List<Point>>)
    private val cache = object : LinkedHashMap<String, List<Polygon>>(12, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Polygon>>?) = size > 8
    }
    @Synchronized private fun tile(row: Int, col: Int): List<Polygon> {
        val key = "water/$row/${Math.floorMod(col, 72)}"
        return cache[key] ?: run {
            val polygons = if (!pack.hasBlock(key)) emptyList() else {
                val bytes = ByteBuffer.wrap(pack.block(key)).order(ByteOrder.LITTLE_ENDIAN)
                fun count(): Int = bytes.int.also { require(it in 0..500_000) }
                List(count()) { Polygon(List(count()) { List(count()) { Point(bytes.double, bytes.double) } }) }.also { require(!bytes.hasRemaining()) }
            }
            cache[key] = polygons
            polygons
        }
    }

    private fun onSegment(p: Point, a: Point, b: Point): Boolean {
        val cross = (p.x - a.x) * (b.y - a.y) - (p.y - a.y) * (b.x - a.x)
        return abs(cross) < 1e-11 && p.x >= min(a.x, b.x) - 1e-10 && p.x <= max(a.x, b.x) + 1e-10 && p.y >= min(a.y, b.y) - 1e-10 && p.y <= max(a.y, b.y) + 1e-10
    }
    private fun inside(point: Point, ring: List<Point>): Boolean {
        var inside = false
        for (i in 1 until ring.size) {
            val a = ring[i - 1]; val b = ring[i]
            if (onSegment(point, a, b)) return true
            if ((a.y > point.y) != (b.y > point.y) && point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        }
        return inside
    }
    fun isSea(point: GeoPoint): Boolean {
        val row = floor((point.latitude + 90) / 5).toInt().coerceIn(0, 35)
        val col = floor((point.longitude + 180) / 5).toInt().coerceIn(0, 71)
        val p = Point(point.longitude, point.latitude)
        return tile(row, col).any { polygon -> polygon.rings.isNotEmpty() && inside(p, polygon.rings[0]) && polygon.rings.drop(1).none { inside(p, it) } }
    }

    fun nearbySea(point: GeoPoint, limitKm: Double = 1.0): GeoPoint? {
        if (isSea(point)) return point
        val row = floor((point.latitude + 90) / 5).toInt().coerceIn(0, 35)
        val col = floor((point.longitude + 180) / 5).toInt().coerceIn(0, 71)
        var best: GeoPoint? = null; var distance = limitKm
        val xScale = cos(Math.toRadians(point.latitude)).coerceAtLeast(.01)
        for (r in max(0, row - 1)..min(35, row + 1)) for (c in col - 1..col + 1) {
            for (polygon in tile(r, c)) for (ring in polygon.rings) for (i in 1 until ring.size) {
                val a = ring[i - 1]; val b = ring[i]
                val dx = (b.x - a.x) * xScale; val dy = b.y - a.y
                val divisor = dx * dx + dy * dy
                val t = if (divisor == 0.0) 0.0 else (((point.longitude - a.x) * xScale * dx + (point.latitude - a.y) * dy) / divisor).coerceIn(0.0, 1.0)
                val x = a.x + t * (b.x - a.x); val y = a.y + t * (b.y - a.y)
                if (x !in -180.0..180.0 || y !in -90.0..90.0) continue
                val candidate = GeoPoint(y, x); val km = point.distanceKm(candidate)
                if (km <= distance) { best = candidate; distance = km }
            }
        }
        return best
    }

    fun clearPath(from: GeoPoint, to: GeoPoint): Boolean {
        val dx = ((to.longitude - from.longitude + 180) % 360 + 360) % 360 - 180
        val dy = to.latitude - from.latitude
        val cuts = mutableListOf(0.0, 1.0)
        val minRow = floor((min(from.latitude, to.latitude) + 90) / 5).toInt().coerceIn(0, 35)
        val maxRow = floor((max(from.latitude, to.latitude) + 90) / 5).toInt().coerceIn(0, 35)
        val c0 = floor((from.longitude + 180) / 5).toInt()
        // The line is at most 30 km. Adjacent tiles suffice except extremely
        // near a pole, where longitude spans more tiles and we include them.
        val reach = ceil(abs(dx) / 5).toInt() + 1
        for (r in minRow..maxRow) for (c in c0 - reach..c0 + reach) {
            for (polygon in tile(r, c)) for (ring in polygon.rings) for (i in 1 until ring.size) {
                val a = ring[i - 1]; val b = ring[i]
                val ax = ((a.x - from.longitude + 180) % 360 + 360) % 360 - 180
                val bx = ax + (((b.x - a.x + 180) % 360 + 360) % 360 - 180)
                val ex = bx - ax; val ey = b.y - a.y
                val determinant = dx * ey - dy * ex
                if (abs(determinant) < 1e-15) continue
                val t = (ax * ey - (a.y - from.latitude) * ex) / determinant
                val u = (ax * dy - (a.y - from.latitude) * dx) / determinant
                if (t > 1e-9 && t < 1 - 1e-9 && u in -1e-9..1.000000001) cuts += t
            }
        }
        cuts.sort()
        for (i in 1 until cuts.size) {
            if (cuts[i] - cuts[i - 1] < 1e-9) continue
            val t = (cuts[i] + cuts[i - 1]) / 2
            val lon = ((from.longitude + t * dx + 180) % 360 + 360) % 360 - 180
            if (!isSea(GeoPoint(from.latitude + t * dy, lon))) return false
        }
        return true
    }
}
