package com.cztask.ui

import com.cztask.data.db.Reminder
import com.cztask.data.time.NextFireCalculator
import com.cztask.data.time.TimeSource
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DATE = DateTimeFormatter.ofPattern("EEE d MMM")

fun formatTimeOfDay(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

fun formatRule(r: Reminder): String {
    val at = formatTimeOfDay(r.timeOfDayMinutes)
    return when (r.daysOfWeekMask) {
        0 -> "${LocalDate.ofEpochDay(r.dateEpochDay ?: 0).format(DATE)} $at"
        127 -> "Daily $at"
        0b0011111 -> "Weekdays $at"
        0b1100000 -> "Weekends $at"
        else -> {
            val days = DayOfWeek.entries
                .filter { r.daysOfWeekMask and (1 shl (it.value - 1)) != 0 }
                .joinToString(",") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
            "$days $at"
        }
    }
}

/** "next 08:00" / "next Tue 08:00" / "done" for the reminder subtitle. */
fun formatNext(r: Reminder, time: TimeSource): String {
    val next = NextFireCalculator.nextOccurrence(r, time.nowUtcMillis(), time.zone())
        ?: return if (r.daysOfWeekMask == 0 && r.lastFiredOccurrenceUtcMillis != null) "done" else "—"
    val zdt = Instant.ofEpochMilli(next).atZone(time.zone())
    val today = Instant.ofEpochMilli(time.nowUtcMillis()).atZone(time.zone()).toLocalDate()
    return when (zdt.toLocalDate()) {
        today -> "next ${zdt.format(TIME)}"
        today.plusDays(1) -> "next ${zdt.format(TIME)} tomorrow"
        else -> "next ${zdt.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${zdt.format(TIME)}"
    }
}

fun formatDuration(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return when {
        m == 0 -> "${s}s"
        s == 0 -> "$m min"
        else -> "${m}m ${s}s"
    }
}
