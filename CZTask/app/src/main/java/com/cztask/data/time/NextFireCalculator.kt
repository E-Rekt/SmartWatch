package com.cztask.data.time

import com.cztask.data.db.Reminder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure occurrence math over a reminder row. Next-fire is DERIVED, never stored:
 * with <50 rows this is microseconds, and it deletes the cache-invalidation
 * problem that clock jumps would otherwise create (the measured failure mode
 * of this device).
 *
 * DST semantics are java.time's: a civil time in a spring-forward gap shifts
 * forward by the gap (02:30 -> 03:30); in a fall-back overlap the earlier
 * offset wins. A daily 08:00 reminder stays 08:00 local across transitions.
 */
object NextFireCalculator {

    /** Repeating reminders may fire late by at most this much (a watch that was
     *  rebooting at 08:58 still delivers the 09:00 pill reminder at 09:20).
     *  Beyond it, the occurrence is skipped — no catch-up storms after long
     *  offline gaps or forward clock jumps. */
    const val LATE_GRACE_MS: Long = 30 * 60_000L

    /** The dedup floor exists to absorb backward CLOCK STEPS, not to be a
     *  future veto: a lastFired stamp recorded while the clock ran fast would
     *  otherwise silence the reminder until wall time catches the bogus stamp
     *  (up to the full clock error — months, on this device's history). A
     *  stamp further than this into the future is provably garbage (that wall
     *  time has not happened) and is ignored. Cost of the bound: one duplicate
     *  fire after a >24 h manual backward correction. */
    const val MAX_DEDUP_FUTURE_MS: Long = 24 * 3_600_000L

    private fun dedupFloor(r: Reminder, nowUtcMillis: Long): Long =
        r.lastFiredOccurrenceUtcMillis?.takeIf { it <= nowUtcMillis + MAX_DEDUP_FUTURE_MS }
            ?: Long.MIN_VALUE

    /** Strictly-future next occurrence as a UTC instant, or null (disabled /
     *  spent one-shot / no future occurrence). Pure function of (row, now, zone). */
    fun nextOccurrence(r: Reminder, nowUtcMillis: Long, zone: ZoneId): Long? {
        if (!r.enabled) return null
        // A future snooze is the reminder's SOLE next occurrence: the user
        // explicitly deferred; the rule resumes after it fires (markFired
        // clears the snooze along with stamping the dedup floor).
        r.snoozeUntilUtcMillis?.let { snooze ->
            if (snooze > nowUtcMillis) return snooze
        }
        val time = LocalTime.ofSecondOfDay(r.timeOfDayMinutes * 60L)
        val floor = dedupFloor(r, nowUtcMillis)

        if (r.daysOfWeekMask == 0) {                       // one-shot
            val date = LocalDate.ofEpochDay(r.dateEpochDay ?: return null)
            val occ = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            return occ.takeIf { it > nowUtcMillis && it > floor }
        }
        val today = Instant.ofEpochMilli(nowUtcMillis).atZone(zone).toLocalDate()
        for (d in 0..7L) {   // 8 days: today-already-passed + single-day mask => next week
            val date = today.plusDays(d)
            if (r.daysOfWeekMask and (1 shl (date.dayOfWeek.value - 1)) == 0) continue
            val occ = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            if (occ > nowUtcMillis && occ > floor) return occ
        }
        return null
    }

    /** One-shot whose moment has passed but which was never fired — due
     *  immediately, with no lateness bound (better late than never for a
     *  deliberate one-off). */
    fun isOverdueOneShot(r: Reminder, nowUtcMillis: Long, zone: ZoneId): Boolean {
        if (!r.enabled || r.daysOfWeekMask != 0 || r.lastFiredOccurrenceUtcMillis != null) return false
        val date = LocalDate.ofEpochDay(r.dateEpochDay ?: return false)
        val time = LocalTime.ofSecondOfDay(r.timeOfDayMinutes * 60L)
        return ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli() <= nowUtcMillis
    }

    /** A one-shot's own instant (regardless of past/future/fired), or null if
     *  the row isn't a one-shot or has no date. */
    fun oneShotOccurrence(r: Reminder, zone: ZoneId): Long? {
        if (r.daysOfWeekMask != 0) return null
        val date = LocalDate.ofEpochDay(r.dateEpochDay ?: return null)
        val time = LocalTime.ofSecondOfDay(r.timeOfDayMinutes * 60L)
        return ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
    }

    /** Most recent missed occurrence of a REPEATING reminder within the grace
     *  window: unfired (occ > lastFired floor), already past (occ <= now), and
     *  late by no more than LATE_GRACE_MS. Returns the occurrence instant to
     *  stamp into markFired, or null. One occurrence at most — never a storm. */
    fun lateRepeatingOccurrence(r: Reminder, nowUtcMillis: Long, zone: ZoneId): Long? {
        if (!r.enabled || r.daysOfWeekMask == 0) return null
        val floor = dedupFloor(r, nowUtcMillis)
        val time = LocalTime.ofSecondOfDay(r.timeOfDayMinutes * 60L)
        val today = Instant.ofEpochMilli(nowUtcMillis).atZone(zone).toLocalDate()
        // Grace is 30 min, so only today and yesterday (midnight-spanning) matter.
        for (d in 0L..1L) {
            val date = today.minusDays(d)
            if (r.daysOfWeekMask and (1 shl (date.dayOfWeek.value - 1)) == 0) continue
            val occ = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            if (occ <= nowUtcMillis && occ > floor && nowUtcMillis - occ <= LATE_GRACE_MS) return occ
        }
        return null
    }
}
