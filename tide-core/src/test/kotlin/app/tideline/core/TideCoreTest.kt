package app.tideline.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class TideCoreTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/$name")!!.use { it.readBytes() }
    private fun astronomy(): Astronomy {
        val data = Json.parseToJsonElement(resource("astronomy_fixture.json").toString(Charsets.UTF_8)).jsonObject
        val buffer = ByteBuffer.allocate(data.int("node_count") * data.int("basis_count") * 16).order(ByteOrder.LITTLE_ENDIAN)
        for (node in data.getValue("envelopes").jsonArray) for (term in node.jsonArray) for (value in term.jsonArray) buffer.putDouble(value.jsonPrimitive.double)
        return Astronomy(data, buffer.array())
    }
    @Test fun genuineReferenceAgreement() {
        val astronomy = astronomy()
        val cases = Json.parseToJsonElement(resource("reference_cases.json").toString(Charsets.UTF_8)).jsonArray
        for (item in cases) {
            val data = item.jsonObject
            val ap = data.getValue("amplitude_phase").jsonArray
            val harmonics = Array(17) { i ->
                val a = ap[i].jsonArray[0].jsonPrimitive.double * .01
                val p = Math.toRadians(ap[i].jsonArray[1].jsonPrimitive.double)
                Complex(a * cos(p), -a * sin(p))
            }
            val signal = astronomy.signal(harmonics)
            val times = data.getValue("epoch_seconds").jsonArray; val heights = data.getValue("heights").jsonArray
            for (i in times.indices) assertEquals(heights[i].jsonPrimitive.double, signal.height(times[i].jsonPrimitive.double), .001)
        }
    }
    @Test fun solarTideHasAnalyticPeriodPhaseAndTurningPoints() {
        val astronomy = astronomy()
        val harmonics = Array(17) { Complex(0.0, 0.0) }
        // Exercise the flat-tide control, then an analytic S2-only case with
        // minor inference removed from the synthetic admittance matrix.
        val zero = astronomy.signal(harmonics)
        val start = Instant.parse("2026-01-01T00:00:01Z").toEpochMilli()
        assertEquals(0.0, zero.height(start), 0.0)
        assertTrue(zero.extrema(start).isEmpty())
        val data = Json.parseToJsonElement(resource("astronomy_fixture.json").toString(Charsets.UTF_8)).jsonObject
        val noInference = buildJsonObject {
            for ((key, value) in data) put(key, value)
            for (key in listOf("mapping_real", "mapping_imag")) putJsonArray(key) {
                for (i in 0 until data.int("basis_count")) add(buildJsonArray { for (j in 0 until 17) add(if (key == "mapping_real" && i == j) 1.0 else 0.0) })
            }
        }
        val buffer = ByteBuffer.allocate(data.int("node_count") * data.int("basis_count") * 16).order(ByteOrder.LITTLE_ENDIAN)
        for (node in data["envelopes"]!!.jsonArray) for (term in node.jsonArray) for (v in term.jsonArray) buffer.putDouble(v.jsonPrimitive.double)
        harmonics[13] = Complex(1.0, 0.0)
        val signal = Astronomy(noInference, buffer.array()).signal(harmonics)
        val midnight = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        for (hour in 0..36) assertEquals(cos(hour * PI / 6), signal.height(midnight + hour * 3_600_000L), 1e-7)
        val events = signal.extrema(start)
        assertFalse(events.first().high)
        assertEquals(Instant.parse("2026-01-01T06:00:00Z").toEpochMilli().toDouble(), events.first().epochMillis.toDouble(), 2000.0)
        assertTrue(events[1].high)
    }
    @Test fun datesOutsideInstalledTableAreRejected() {
        val astro = astronomy(); val signal = astro.signal(Array(17) { Complex(0.0, 0.0) })
        assertThrows(IllegalArgumentException::class.java) { signal.height(astro.startEpochSeconds - 1.0) }
        assertThrows(IllegalArgumentException::class.java) { signal.height(astro.endEpochSeconds + 1.0) }
    }
    @Test fun nativeLosslessDecoderMatchesOriginalCoastalCoefficientsAndWrapsLongitude() {
        val bytes = resource("cell_fixture.tpk")
        ModelPack(MemorySource(bytes)).use { pack ->
            val checks = Json.parseToJsonElement(resource("cell_checks.json").toString(Charsets.UTF_8)).jsonArray
            for (check in checks) {
                val c = check.jsonObject; val row = c.int("row"); val col = c.int("col")
                for (wrapped in listOf(col, col + 2880, col - 2880)) {
                    val values = pack.cell(row, wrapped)!!
                    for (i in 0 until 17) {
                        assertEquals(c["real"]!!.jsonArray[i].jsonPrimitive.double, values[i].real, 1e-13)
                        assertEquals(c["imag"]!!.jsonArray[i].jsonPrimitive.double, values[i].imag, 1e-13)
                    }
                }
            }
            assertNull(pack.cell(-1, 0))
        }
        val bad = bytes.copyOf().also { it[0] = 0 }
        assertThrows(IllegalArgumentException::class.java) { ModelPack(MemorySource(bad)) }
        ModelPack(MemorySource(bytes.copyOf(bytes.size - 5))).use { pack ->
            val check = Json.parseToJsonElement(resource("cell_checks.json").toString(Charsets.UTF_8)).jsonArray.last().jsonObject
            assertThrows(Exception::class.java) { pack.cell(check.int("row"), check.int("col")) }
        }
    }
    @Test fun datelineDistanceAndComplexPhaseWrap() {
        assertEquals(0.0, GeoPoint(0.0, 180.0).distanceKm(GeoPoint(0.0, -180.0)), 1e-8)
        assertEquals(111.195, GeoPoint(0.0, 0.0).distanceKm(GeoPoint(0.0, 1.0)), .001)
        val a = Complex(cos(Math.toRadians(359.0)), -sin(Math.toRadians(359.0)))
        val b = Complex(cos(Math.toRadians(1.0)), -sin(Math.toRadians(1.0)))
        val average = (a + b) * .5
        assertTrue(average.real > .999); assertEquals(0.0, average.imag, 1e-14)
    }
    private class MemorySource(private val bytes: ByteArray) : DataSource {
        override val size = bytes.size.toLong()
        override fun read(offset: Long, count: Int): ByteArray { require(offset >= 0 && offset + count <= size); return bytes.copyOfRange(offset.toInt(), offset.toInt() + count) }
        override fun close() {}
    }
}
