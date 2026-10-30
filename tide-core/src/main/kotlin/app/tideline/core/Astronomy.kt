package app.tideline.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.*
import kotlin.math.*

/** Exact carrier frequencies with daily, slowly varying FES nodal/phase envelopes.
 * Tables and the minor-constituent admittance matrix are generated from pinned
 * pyTMD 2.2.9. Heights are calculated from local harmonics at each UTC instant.
 */
class Astronomy(metadata: JsonObject, bytes: ByteArray) {
    val startEpochSeconds = metadata.long("start_epoch_seconds")
    private val step = metadata.long("step_seconds")
    private val nodes = metadata.int("node_count")
    private val count = metadata.int("basis_count")
    val endEpochSeconds = startEpochSeconds + (nodes - 1) * step
    private val carrierEpoch = metadata.long("carrier_epoch_seconds")
    private val omega = metadata.getValue("omega_rad_per_second").jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
    private val mapping = Array(count) { i ->
        val real = metadata.getValue("mapping_real").jsonArray[i].jsonArray
        val imag = metadata.getValue("mapping_imag").jsonArray[i].jsonArray
        Array(17) { j -> Complex(real[j].jsonPrimitive.double, imag[j].jsonPrimitive.double) }
    }
    private val envelope: DoubleArray
    init {
        require(nodes >= 2 && count in 17..64 && step == 86400L && bytes.size == nodes * count * 2 * 8)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        envelope = DoubleArray(nodes * count * 2) { buffer.double.also { require(it.isFinite()) } }
    }

    fun signal(harmonics: Array<Complex>): TideSignal {
        require(harmonics.size == 17)
        val expanded = Array(count) { i ->
            var value = Complex(0.0, 0.0)
            for (j in 0 until 17) value += mapping[i][j] * harmonics[j]
            value
        }
        return TideSignal(this, expanded)
    }

    internal fun height(coefficients: Array<Complex>, epochSeconds: Double): Double {
        require(epochSeconds.isFinite() && epochSeconds >= startEpochSeconds && epochSeconds <= endEpochSeconds) {
            "Prediction date outside installed astronomy tables (2020–2040). Update the data package."
        }
        val day = (epochSeconds - startEpochSeconds) / step
        val index = floor(day).toInt().coerceAtMost(nodes - 2)
        val fraction = day - index
        val carrier = epochSeconds - carrierEpoch
        var height = 0.0
        for (i in 0 until count) {
            val a = (index * count + i) * 2; val b = a + count * 2
            val real = envelope[a] + fraction * (envelope[b] - envelope[a])
            val imag = envelope[a + 1] + fraction * (envelope[b + 1] - envelope[a + 1])
            val phase = omega[i] * carrier
            val cosine = cos(phase); val sine = sin(phase)
            height += coefficients[i].real * (real * cosine - imag * sine) - coefficients[i].imag * (real * sine + imag * cosine)
        }
        check(height.isFinite())
        return height
    }
}

class TideSignal internal constructor(private val astronomy: Astronomy, private val coefficients: Array<Complex>) {
    fun height(epochSeconds: Double) = astronomy.height(coefficients, epochSeconds)
    fun height(epochMillis: Long) = height(epochMillis / 1000.0)
    fun slopeMetresPerHour(epochMillis: Long): Double {
        val a = max(epochMillis - 30_000, astronomy.startEpochSeconds * 1000)
        val b = min(epochMillis + 30_000, astronomy.endEpochSeconds * 1000)
        require(b > a) { "Date outside installed astronomy tables" }
        return (height(b) - height(a)) * 3_600_000.0 / (b - a)
    }

    fun extrema(afterMillis: Long, hours: Int = 48): List<TideEvent> {
        val result = mutableListOf<TideEvent>()
        val increment = 600_000L
        var left = max(afterMillis - increment, astronomy.startEpochSeconds * 1000)
        var previous = slopeMetresPerHour(left)
        val end = min(afterMillis + hours * 3_600_000L, astronomy.endEpochSeconds * 1000)
        while (left < end) {
            val right = min(left + increment, end)
            val derivative = slopeMetresPerHour(right)
            if ((previous > 0 && derivative <= 0) || (previous < 0 && derivative >= 0)) {
                var a = left; var b = right
                val high = previous > 0
                while (b - a > 1000) {
                    val middle = (a + b) / 2
                    if ((slopeMetresPerHour(middle) > 0) == high) a = middle else b = middle
                }
                val instant = (a + b) / 2
                val value = height(instant)
                val before = max(astronomy.startEpochSeconds * 1000, instant - 1_200_000)
                val after = min(astronomy.endEpochSeconds * 1000, instant + 1_200_000)
                val curvature = abs(value - (height(before) + height(after)) / 2)
                if (instant > afterMillis && curvature > .0002 && (result.isEmpty() || instant - result.last().epochMillis > 60_000))
                    result += TideEvent(instant, value, high)
            }
            left = right; previous = derivative
        }
        return result
    }
}

data class TideEvent(val epochMillis: Long, val metres: Double, val high: Boolean)
