package app.tideline.core

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.InflaterInputStream
import kotlinx.serialization.json.*
import kotlin.math.*

interface DataSource : Closeable {
    val size: Long
    fun read(offset: Long, count: Int): ByteArray
}

class FileSource(path: String) : DataSource {
    private val file = RandomAccessFile(path, "r")
    override val size get() = file.length()
    @Synchronized override fun read(offset: Long, count: Int): ByteArray {
        require(offset >= 0 && count >= 0 && offset + count <= size) { "Truncated data package" }
        return ByteArray(count).also { file.seek(offset); file.readFully(it) }
    }
    override fun close() = file.close()
}

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init { require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) }
    fun distanceKm(other: GeoPoint): Double {
        val a = Math.toRadians(latitude); val b = Math.toRadians(other.latitude)
        val dLat = b - a; val dLon = Math.toRadians(other.longitude - longitude)
        return 6371.0088 * 2 * asin(sqrt((sin(dLat / 2).pow(2) + cos(a) * cos(b) * sin(dLon / 2).pow(2)).coerceIn(0.0, 1.0)))
    }
}

data class Complex(val real: Double, val imag: Double) {
    operator fun plus(other: Complex) = Complex(real + other.real, imag + other.imag)
    operator fun times(other: Complex) = Complex(real * other.real - imag * other.imag, real * other.imag + imag * other.real)
    operator fun times(weight: Double) = Complex(real * weight, imag * weight)
}

internal fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
internal fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
internal fun JsonObject.double(key: String) = getValue(key).jsonPrimitive.double
internal fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

class ModelPack(private val source: DataSource) : Closeable {
    val header: JsonObject
    private val payloadStart: Long
    private val entries: Map<String, JsonObject>
    private val tideCache = object : LinkedHashMap<String, TideTile>(12, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TideTile>?) = size > 8
    }
    val latitude0: Double
    val longitude0: Double
    val rows: Int
    val cols: Int
    val step: Double
    val astronomy: Astronomy by lazy { Astronomy(header.getValue("astronomy").jsonObject, block("astronomy")) }
    val water: WaterAtlas by lazy { WaterAtlas(this) }

    init {
        require(source.size >= 12) { "Truncated data package" }
        val prefix = source.read(0, 12)
        require(String(prefix, 0, 8, Charsets.US_ASCII) == "TIDEPK01") { "Unsupported tide package" }
        val count = ByteBuffer.wrap(prefix, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(count in 1..4_000_000 && count.toLong() + 12 <= source.size) { "Invalid package index" }
        header = Json.parseToJsonElement(source.read(12, count).toString(Charsets.UTF_8)).jsonObject
        require(header.int("version") == 1 && header.string("model") == "EOT20 final") { "Incompatible tide package version" }
        require(header["constituents"]!!.jsonArray.size == 17) { "Unexpected constituent count" }
        payloadStart = 12L + count
        entries = header.getValue("entries").jsonArray.associate { it.jsonObject.string("key") to it.jsonObject }
        latitude0 = header.double("lat0"); longitude0 = header.double("lon0")
        rows = header.int("rows"); cols = header.int("cols"); step = header.double("step")
        require(rows == 1441 && cols == 2880 && step == .125 && header.int("tile_side") == 40)
    }

    fun hasBlock(key: String) = entries.containsKey(key)

    private val wetPositions = mutableMapOf<String, IntArray>()

    /** Exact nearest stored wet cell; conservative tile bounds also work at the dateline/poles. */
    @Synchronized fun nearestCell(point: GeoPoint): SupportCell? {
        data class Tile(val key: String, val row: Int, val col: Int, val columns: Int, val lowerBound: Double)
        val tiles = entries.filterKeys { it.startsWith("tide/") }.map { (key, meta) ->
            val parts = key.split('/'); val row = parts[1].toInt(); val col = parts[2].toInt()
            val latSpan = (meta.int("rows") - 1) * step
            val lonSpan = (meta.int("cols") - 1) * step
            val centre = GeoPoint(latitude0 + row * step + latSpan / 2,
                ((longitude0 + col * step + lonSpan / 2 + 180) % 360) - 180)
            // A meridian plus a parallel bounds the distance to every point in the tile.
            val radius = Math.toRadians((latSpan + lonSpan) / 2) * 6371.0088
            Tile(key, row, col, meta.int("cols"), max(0.0, point.distanceKm(centre) - radius))
        }.sortedBy { it.lowerBound }
        var best: SupportCell? = null
        for (tile in tiles) {
            if (tile.lowerBound > (best?.distanceKm ?: Double.POSITIVE_INFINITY) + 1e-9) break
            val positions = wetPositions.getOrPut(tile.key) {
                val meta = entries.getValue(tile.key); val raw = block(tile.key)
                (0 until meta.int("rows") * tile.columns)
                    .filter { (raw[it / 8].toInt() ushr (it % 8)) and 1 != 0 }.toIntArray()
            }
            for (index in positions) {
                val row = tile.row + index / tile.columns; val col = tile.col + index % tile.columns
                val candidate = point(row, col); val distance = point.distanceKm(candidate)
                val previous = best
                if (previous == null || distance < previous.distanceKm ||
                    (distance == previous.distanceKm && (row < previous.row || row == previous.row && col < previous.col)))
                    best = SupportCell(row, col, candidate, distance, 1.0)
            }
        }
        return best
    }

    @Synchronized fun block(key: String): ByteArray {
        val entry = entries[key] ?: throw IllegalArgumentException("Missing data tile: $key")
        val length = entry.int("length"); val expected = entry.int("raw_length")
        require(length in 1..8_000_000 && expected in 1..8_000_000) { "Invalid data tile size" }
        val offset = payloadStart + entry.long("offset")
        require(offset >= payloadStart && offset + length <= source.size) { "Truncated data tile" }
        val compressed = source.read(offset, length)
        val raw = InflaterInputStream(ByteArrayInputStream(compressed)).use { stream ->
            val bytes = ByteArray(expected)
            var position = 0
            while (position < bytes.size) {
                val read = stream.read(bytes, position, bytes.size - position)
                if (read < 0) throw EOFException("Truncated compressed tile")
                position += read
            }
            require(stream.read() == -1) { "Oversized data tile" }
            bytes
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(digest == entry.string("sha256")) { "Corrupt data tile" }
        return raw
    }

    @Synchronized fun cell(row: Int, col: Int): Array<Complex>? {
        if (row !in 0 until rows) return null
        val c = Math.floorMod(col, cols)
        val key = "tide/${row / 40 * 40}/${c / 40 * 40}"
        if (!hasBlock(key)) return null
        val tile = tideCache[key] ?: TideTile(entries.getValue(key), block(key)).also { tideCache[key] = it }
        return tile.cell(row % 40, c % 40)
    }

    fun point(row: Int, col: Int) = GeoPoint(latitude0 + row * step, ((longitude0 + Math.floorMod(col, cols) * step + 180) % 360) - 180)

    override fun close() = source.close()
}

private class TideTile(meta: JsonObject, raw: ByteArray) {
    private val rows = meta.int("rows"); private val cols = meta.int("cols")
    private val cells = meta.int("cells")
    private val position = IntArray(rows * cols) { -1 }
    private val words: LongArray
    init {
        val maskBytes = meta.int("mask_bytes")
        require(rows in 1..40 && cols in 1..40 && cells in 1..rows * cols)
        require(maskBytes == (rows * cols + 7) / 8 && raw.size == maskBytes + 17 * 2 * cells * 8)
        var at = 0
        for (i in position.indices) if ((raw[i / 8].toInt() ushr (i % 8)) and 1 != 0) position[i] = at++
        require(at == cells) { "Invalid wet mask" }
        val count = 17 * 2 * cells
        words = LongArray(count)
        // Undo byte shuffle, then XOR accumulation within constituent/component rows.
        for (i in 0 until count) {
            var value = 0L
            for (byte in 0..7) value = value or ((raw[maskBytes + byte * count + i].toLong() and 255) shl (byte * 8))
            words[i] = if (i % cells == 0) value else value xor words[i - 1]
        }
    }
    fun cell(row: Int, col: Int): Array<Complex>? {
        if (row !in 0 until rows || col !in 0 until cols) return null
        val index = position[row * cols + col]
        if (index < 0) return null
        return Array(17) { c ->
            val amplitude = Double.fromBits(words[(c * 2) * cells + index]) * .01
            val phase = Math.toRadians(Double.fromBits(words[(c * 2 + 1) * cells + index]))
            require(amplitude.isFinite() && phase.isFinite()) { "Invalid harmonic coefficient" }
            Complex(amplitude * cos(phase), -amplitude * sin(phase))
        }
    }
}
