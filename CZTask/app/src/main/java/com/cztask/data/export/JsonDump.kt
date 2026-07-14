package com.cztask.data.export

import com.cztask.data.db.Reminder
import com.cztask.data.db.Task
import com.cztask.data.db.TimerPreset

/**
 * Data escape hatch. allowBackup=false + no destructive migrations means the
 * only copy of the user's data lives in one SQLite file on a 5-year-old watch;
 * this renders it as JSON so `adb shell "run-as com.cztask cat files/dump.json"`
 * (or a future settings action) can get it out. Pure Kotlin: JVM-testable.
 */
object JsonDump {
    fun dump(tasks: List<Task>, reminders: List<Reminder>, presets: List<TimerPreset>): String {
        val sb = StringBuilder(256)
        sb.append("{\"version\":1,\"task\":[")
        tasks.joinTo(sb, ",") { t ->
            obj(
                "id" to t.id, "title" to str(t.title), "done" to t.done,
                "created_at_utc_millis" to t.createdAtUtcMillis, "source" to t.source,
            )
        }
        sb.append("],\"reminder\":[")
        reminders.joinTo(sb, ",") { r ->
            obj(
                "id" to r.id, "task_id" to r.taskId, "label" to str(r.label),
                "time_of_day_minutes" to r.timeOfDayMinutes,
                "days_of_week_mask" to r.daysOfWeekMask,
                "date_epoch_day" to r.dateEpochDay, "enabled" to r.enabled,
                "last_fired_occurrence_utc_millis" to r.lastFiredOccurrenceUtcMillis,
            )
        }
        sb.append("],\"timer_preset\":[")
        presets.joinTo(sb, ",") { p ->
            obj("id" to p.id, "label" to str(p.label), "duration_seconds" to p.durationSeconds)
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun obj(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${v ?: "null"}" }

    private fun str(s: String?): String? = s?.let {
        buildString(it.length + 2) {
            append('"')
            for (c in it) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }
}
