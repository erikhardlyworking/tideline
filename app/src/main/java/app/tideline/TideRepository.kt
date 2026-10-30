package app.tideline

import android.app.Application
import android.content.Context
import android.content.res.AssetFileDescriptor
import app.tideline.core.*
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.*

class TideApplication : Application()

private class AssetSource(private val descriptor: AssetFileDescriptor) : DataSource {
    private val stream = FileInputStream(descriptor.fileDescriptor)
    private val channel = stream.channel
    override val size = descriptor.length
    @Synchronized override fun read(offset: Long, count: Int): ByteArray {
        require(offset >= 0 && count >= 0 && offset + count <= size) { "Truncated installed data package" }
        val bytes = ByteArray(count); val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) check(channel.read(buffer, descriptor.startOffset + offset + buffer.position()) > 0)
        return bytes
    }
    override fun close() { stream.close(); descriptor.close() }
}

data class TideSnapshot(
    val startMillis: Long,
    val label: String,
    val modelLocation: ModelLocation? = null,
    val signal: TideSignal? = null,
    val currentMetres: Double = 0.0,
    val slope: Double = 0.0,
    val maximum24: Double = 0.0,
    val minimum24: Double = 0.0,
    val scaleMetres: Double = .25,
    val nextHigh: TideEvent? = null,
    val nextLow: TideEvent? = null,
    val error: String? = null,
    val coverageProblem: LocationProblem? = null,
    val needsLocation: Boolean = false
) {
    fun samples(hours: Int, count: Int): DoubleArray? = signal?.let { s -> DoubleArray(count) { i -> s.height(startMillis + i * hours * 3_600_000L / (count - 1)) } }
}

object TideRepository {
    private data class LocationQuery(val point: GeoPoint, val shoreLimitKm: Double)
    @Volatile private var model: TideModel? = null
    private val locations = object : LinkedHashMap<LocationQuery, LocationResult>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocationQuery, LocationResult>?) = size > 8
    }
    @Synchronized private fun model(context: Context): TideModel = model ?: TideModel(ModelPack(AssetSource(context.applicationContext.assets.openFd("tideline-global.tpk")))).also { model = it }

    @Synchronized fun snapshot(context: Context, settings: WidgetSettings, at: Long = System.currentTimeMillis()): TideSnapshot {
        val point = settings.point() ?: return TideSnapshot(at, "", error = context.getString(R.string.choose_location), needsLocation = true)
        return try {
            val model = model(context)
            val query = LocationQuery(point, if (settings.followCurrent) (settings.accuracy / 1000.0 + 1).coerceIn(1.0, 5.0) else 1.0)
            val located = locations[query] ?: model.locate(point, query.shoreLimitKm).also { locations[query] = it }
            if (located is LocationResult.Unavailable) TideSnapshot(at, settings.label(), error = located.reason, coverageProblem = located.problem)
            else {
                val support = (located as LocationResult.Available).location
                val signal = model.signal(support)
                val events = signal.extrema(at)
                // The scale sees a much longer interval than the visible window.
                val scaleValues = DoubleArray(289) { i -> signal.height(at - 12 * 3_600_000L + i * 600_000L) }
                val future = DoubleArray(145) { i -> signal.height(at + i * 600_000L) }
                val max24 = max(future.max(), events.filter { it.epochMillis <= at + 86_400_000 }.maxOfOrNull { it.metres } ?: future.max())
                val min24 = min(future.min(), events.filter { it.epochMillis <= at + 86_400_000 }.minOfOrNull { it.metres } ?: future.min())
                TideSnapshot(at, settings.label(), support, signal, signal.height(at), signal.slopeMetresPerHour(at), max24, min24,
                    max(.15, scaleValues.maxOf { abs(it) } * 1.13), events.firstOrNull { it.high }, events.firstOrNull { !it.high })
            }
        } catch (e: Exception) {
            TideSnapshot(at, settings.label(), error = e.message ?: "Installed tide data is unavailable.")
        }
    }
}
