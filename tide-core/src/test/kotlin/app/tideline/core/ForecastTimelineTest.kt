package app.tideline.core

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ForecastTimelineTest {
    @Test fun oddRefreshTimeKeepsQuarterHourClockTicksAndTrueWindow() {
        val start = Instant.parse("2026-10-30T09:07:34Z").toEpochMilli()
        val ticks = ForecastTimeline.ticks(start, 3, ZoneId.of("UTC"))
        assertEquals(12, ticks.size)
        assertEquals(Instant.parse("2026-10-30T09:15:00Z").toEpochMilli(), ticks.first().epochMillis)
        assertEquals(Instant.parse("2026-10-30T12:00:00Z").toEpochMilli(), ticks.last().epochMillis)
        assertEquals(3, ticks.count { it.wholeHour })
        assertTrue(ticks.all { it.epochMillis in start..start + 10_800_000L && it.minute in listOf(0,15,30,45) })
    }
    @Test fun localTicksWorkWithQuarterHourOffsetsAndHalfHourDaylightSaving() {
        for ((zone, date) in listOf("Asia/Kathmandu" to "2026-10-30T09:07:34Z", "Australia/Lord_Howe" to "2026-10-03T15:00:00Z")) {
            val start = Instant.parse(date).toEpochMilli(); val z = ZoneId.of(zone)
            val ticks = ForecastTimeline.ticks(start, 3, z)
            assertTrue(ticks.size >= 8)
            assertTrue(ticks.all { Instant.ofEpochMilli(it.epochMillis).atZone(z).minute in listOf(0,15,30,45) })
            assertTrue(ticks.zipWithNext().all { (a,b) -> b.epochMillis > a.epochMillis })
        }
    }
    @Test fun repeatedHourKeepsDistinctChronologicalInstants() {
        val ticks = ForecastTimeline.ticks(Instant.parse("2026-10-25T00:00:00Z").toEpochMilli(), 3, ZoneId.of("Europe/Oslo"))
        assertEquals(13, ticks.size)
        assertEquals(4, ticks.count { it.wholeHour })
        assertEquals(ticks.size, ticks.map { it.epochMillis }.distinct().size)
    }
}
