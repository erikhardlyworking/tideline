package app.tideline.core

import java.time.Instant
import java.time.ZoneId

data class ClockTick(val epochMillis: Long, val wholeHour: Boolean, val minute: Int)

/** Clock-aligned ticks inside the real forecast interval, including offset/DST changes. */
object ForecastTimeline {
    fun ticks(startMillis: Long, hours: Int, zone: ZoneId): List<ClockTick> {
        require(hours in 1..24)
        val minutes = when { hours <= 4 -> 20; hours <= 6 -> 30; hours <= 12 -> 60; else -> 120 }
        val local = Instant.ofEpochMilli(startMillis).atZone(zone)
        var at = local.withSecond(0).withNano(0)
        val end = startMillis + hours * 3_600_000L
        val result = mutableListOf<ClockTick>()
        while (at.toInstant().toEpochMilli() <= end) {
            val epoch = at.toInstant().toEpochMilli()
            val aligned = if (minutes <= 60) at.minute % minutes == 0 else at.minute == 0 && at.hour % 2 == 0
            if (epoch >= startMillis && aligned) result += ClockTick(epoch, at.minute == 0, at.minute)
            at = at.plusMinutes(1)
        }
        return result
    }
}
