package com.cztask

import com.cztask.data.db.Reminder
import com.cztask.data.time.NextFireCalculator
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed zone with DST so results are deterministic regardless of PC/watch zone. */
private val ZONE: ZoneId = ZoneId.of("America/New_York")

private fun utc(date: LocalDate, time: LocalTime): Long =
    ZonedDateTime.of(date, time, ZONE).toInstant().toEpochMilli()

private const val ALL_DAYS = 0b1111111
private const val MONDAY = 1 shl 0

private fun daily(at: LocalTime, lastFired: Long? = null, enabled: Boolean = true) = Reminder(
    id = 1, timeOfDayMinutes = at.hour * 60 + at.minute,
    daysOfWeekMask = ALL_DAYS, enabled = enabled, lastFiredOccurrenceUtcMillis = lastFired,
)

private fun oneShot(date: LocalDate, at: LocalTime, lastFired: Long? = null) = Reminder(
    id = 2, timeOfDayMinutes = at.hour * 60 + at.minute,
    daysOfWeekMask = 0, dateEpochDay = date.toEpochDay(), lastFiredOccurrenceUtcMillis = lastFired,
)

class NextFireCalculatorTest {

    private val monday: LocalDate = LocalDate.of(2026, 7, 13) // a Monday
    private val eight: LocalTime = LocalTime.of(8, 0)

    @Test fun `daily 0800 at 0700 fires today`() {
        val now = utc(monday, LocalTime.of(7, 0))
        assertEquals(utc(monday, eight), NextFireCalculator.nextOccurrence(daily(eight), now, ZONE))
    }

    @Test fun `daily 0800 just past 0800 fires tomorrow - strict future`() {
        val now = utc(monday, eight) + 1
        assertEquals(utc(monday.plusDays(1), eight), NextFireCalculator.nextOccurrence(daily(eight), now, ZONE))
    }

    @Test fun `weekly monday-only queried on tuesday fires next monday`() {
        val r = daily(eight).copy(daysOfWeekMask = MONDAY)
        val now = utc(monday.plusDays(1), LocalTime.NOON)
        assertEquals(utc(monday.plusDays(7), eight), NextFireCalculator.nextOccurrence(r, now, ZONE))
    }

    @Test fun `weekday mask friday evening fires monday`() {
        val weekdays = 0b0011111
        val r = daily(LocalTime.of(18, 0)).copy(daysOfWeekMask = weekdays)
        val friday = monday.plusDays(4)
        val now = utc(friday, LocalTime.of(18, 1))
        assertEquals(utc(monday.plusDays(7), LocalTime.of(18, 0)), NextFireCalculator.nextOccurrence(r, now, ZONE))
    }

    @Test fun `future one-shot fires at its instant`() {
        val r = oneShot(monday.plusDays(3), eight)
        val now = utc(monday, LocalTime.NOON)
        assertEquals(utc(monday.plusDays(3), eight), NextFireCalculator.nextOccurrence(r, now, ZONE))
    }

    @Test fun `past unfired one-shot is overdue not scheduled - the missed-while-clock-wrong case`() {
        val r = oneShot(monday.minusDays(2), eight)
        val now = utc(monday, LocalTime.NOON)
        assertNull(NextFireCalculator.nextOccurrence(r, now, ZONE))
        assertTrue(NextFireCalculator.isOverdueOneShot(r, now, ZONE))
        assertEquals(utc(monday.minusDays(2), eight), NextFireCalculator.oneShotOccurrence(r, ZONE))
    }

    @Test fun `fired one-shot is spent forever`() {
        val occ = utc(monday.minusDays(2), eight)
        val r = oneShot(monday.minusDays(2), eight, lastFired = occ)
        val now = utc(monday, LocalTime.NOON)
        assertNull(NextFireCalculator.nextOccurrence(r, now, ZONE))
        assertFalse(NextFireCalculator.isOverdueOneShot(r, now, ZONE))
    }

    @Test fun `disabled reminder yields nothing`() {
        val now = utc(monday, LocalTime.of(7, 0))
        assertNull(NextFireCalculator.nextOccurrence(daily(eight, enabled = false), now, ZONE))
    }

    @Test fun `backward clock jump does not double-fire - the auto_time correction case`() {
        // Fired today at 09:00; clock then stepped back to 08:58.
        val nine = LocalTime.of(9, 0)
        val firedOcc = utc(monday, nine)
        val r = daily(nine, lastFired = firedOcc)
        val now = utc(monday, LocalTime.of(8, 58))
        assertEquals(utc(monday.plusDays(1), nine), NextFireCalculator.nextOccurrence(r, now, ZONE))
    }

    @Test fun `15-month forward jump yields exactly one next occurrence - no catch-up storm`() {
        val r = daily(eight, lastFired = utc(LocalDate.of(2025, 4, 22), eight))
        val now = utc(monday, LocalTime.NOON) // clock corrected +15 months
        val next = NextFireCalculator.nextOccurrence(r, now, ZONE)
        assertEquals(utc(monday.plusDays(1), eight), next)
        // and no bounded-grace late fire from 4 hours ago:
        assertNull(NextFireCalculator.lateRepeatingOccurrence(r, now, ZONE))
    }

    @Test fun `late repeating within grace fires the missed occurrence once`() {
        val nine = LocalTime.of(9, 0)
        val r = daily(nine)
        val now = utc(monday, LocalTime.of(9, 20)) // 20 min late, grace is 30
        assertEquals(utc(monday, nine), NextFireCalculator.lateRepeatingOccurrence(r, now, ZONE))
        // once fired, it is not offered again
        val fired = r.copy(lastFiredOccurrenceUtcMillis = utc(monday, nine))
        assertNull(NextFireCalculator.lateRepeatingOccurrence(fired, now, ZONE))
    }

    @Test fun `future-dated lastFired stamp cannot silence a repeating reminder`() {
        // The blocker class: fired while the clock ran 3 days fast, then the
        // clock was corrected backward. The bogus future stamp must be ignored,
        // not honored as a floor that mutes the reminder for 3 days.
        val nine = LocalTime.of(9, 0)
        val bogusFuture = utc(monday.plusDays(3), nine)
        val r = daily(nine, lastFired = bogusFuture)
        val now = utc(monday, LocalTime.of(8, 0))
        assertEquals(utc(monday, nine), NextFireCalculator.nextOccurrence(r, now, ZONE))
    }

    @Test fun `sunday bit six maps to sunday`() {
        val sunday = LocalDate.of(2026, 7, 19)
        val r = daily(eight).copy(daysOfWeekMask = 1 shl 6)
        val now = utc(monday, LocalTime.NOON)
        assertEquals(utc(sunday, eight), NextFireCalculator.nextOccurrence(r, now, ZONE))
    }

    @Test fun `late repeating at exactly the grace bound still fires`() {
        val nine = LocalTime.of(9, 0)
        val now = utc(monday, nine) + NextFireCalculator.LATE_GRACE_MS
        assertEquals(utc(monday, nine), NextFireCalculator.lateRepeatingOccurrence(daily(nine), now, ZONE))
    }

    @Test fun `late repeating beyond grace is skipped`() {
        val nine = LocalTime.of(9, 0)
        val now = utc(monday, LocalTime.of(9, 31))
        assertNull(NextFireCalculator.lateRepeatingOccurrence(daily(nine), now, ZONE))
    }

    @Test fun `late repeating spanning midnight is found on yesterday`() {
        val lateNight = LocalTime.of(23, 50)
        val r = daily(lateNight)
        val now = utc(monday.plusDays(1), LocalTime.of(0, 10)) // 20 min after Mon 23:50
        assertEquals(utc(monday, lateNight), NextFireCalculator.lateRepeatingOccurrence(r, now, ZONE))
    }

    @Test fun `future snooze is the sole next occurrence`() {
        val nine = LocalTime.of(9, 0)
        val snooze = utc(monday, LocalTime.of(14, 30))
        val r = daily(nine).copy(snoozeUntilUtcMillis = snooze)
        val now = utc(monday, LocalTime.NOON)
        assertEquals(snooze, NextFireCalculator.nextOccurrence(r, now, ZONE))
    }

    @Test fun `elapsed snooze falls back to the rule`() {
        val nine = LocalTime.of(9, 0)
        val r = daily(nine).copy(
            snoozeUntilUtcMillis = utc(monday, LocalTime.of(11, 0)),
            lastFiredOccurrenceUtcMillis = utc(monday, LocalTime.of(11, 0)),
        )
        val now = utc(monday, LocalTime.NOON)
        // Snooze already consumed (markFired stamped it): tomorrow 09:00.
        assertEquals(utc(monday.plusDays(1), nine), NextFireCalculator.nextOccurrence(r, now, ZONE))
    }

    @Test fun `snooze survives a backward clock step without double-fire risk`() {
        val nine = LocalTime.of(9, 0)
        val snooze = utc(monday, LocalTime.of(14, 30))
        val r = daily(nine).copy(snoozeUntilUtcMillis = snooze)
        // Clock stepped back before the snooze: still the sole next occurrence.
        val now = utc(monday, LocalTime.of(8, 0))
        assertEquals(snooze, NextFireCalculator.nextOccurrence(r, now, ZONE))
    }

    @Test fun `DST gap one-shot resolves forward and stays schedulable`() {
        // US spring-forward 2026-03-08: 02:30 EST does not exist; java.time shifts to 03:30 EDT.
        val gapDate = LocalDate.of(2026, 3, 8)
        val r = oneShot(gapDate, LocalTime.of(2, 30))
        val now = utc(gapDate, LocalTime.of(1, 0))
        val next = NextFireCalculator.nextOccurrence(r, now, ZONE)
        assertNotNull(next)
        // Independently derived, not computed with the code's own expression:
        // 02:30 EST does not exist on 2026-03-08; resolves to 03:30 EDT = 07:30Z.
        assertEquals(1_772_955_000_000L, next)
    }

    @Test fun `daily 0800 stays 0800 civil across DST - UTC instant shifts one hour`() {
        val beforeGap = LocalDate.of(2026, 3, 7)
        val afterGap = LocalDate.of(2026, 3, 8)
        val nextBefore = NextFireCalculator.nextOccurrence(daily(eight), utc(beforeGap, LocalTime.of(7, 0)), ZONE)!!
        val nextAfter = NextFireCalculator.nextOccurrence(daily(eight), utc(afterGap, LocalTime.of(7, 0)), ZONE)!!
        // 8:00 EST vs 8:00 EDT: civil difference is 24h, UTC difference is 23h.
        assertEquals(23 * 3_600_000L, nextAfter - nextBefore)
    }
}
