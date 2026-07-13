package com.cztask.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Column names are explicit snake_case so the on-disk schema is immune to
// Kotlin property renames — schema stability is a migration-friendliness win.

@Entity(tableName = "task")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val done: Boolean = false,
    @ColumnInfo(name = "created_at_utc_millis") val createdAtUtcMillis: Long,
    // 0 = manual entry; 1 = voice capture (step 5). Costs one column now,
    // removes the only plausible step-5 migration.
    val source: Int = SOURCE_MANUAL,
) {
    companion object {
        const val SOURCE_MANUAL = 0
        const val SOURCE_VOICE = 1
    }
}

@Entity(
    tableName = "reminder",
    foreignKeys = [ForeignKey(
        entity = Task::class,
        parentColumns = ["id"], childColumns = ["task_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("task_id")],
)
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Nullable: a standalone reminder ("take pills, daily 09:00") must not
    // require a never-completable pseudo-task in the task list.
    @ColumnInfo(name = "task_id") val taskId: Long? = null,
    // Display text for standalone reminders. Invariant (repository-enforced):
    // taskId != null OR label is non-blank.
    val label: String? = null,
    // Civil local fire time, 0..1439. Rules are stored as civil time, never as
    // UTC instants: "08:00 daily" must survive DST, zone changes, and the
    // measured 15-month clock error. UTC instants exist only transiently in
    // NextFireCalculator.
    @ColumnInfo(name = "time_of_day_minutes") val timeOfDayMinutes: Int,
    // bit0 = Monday … bit6 = Sunday. 0 = one-shot (dateEpochDay required),
    // 127 = daily, any other set = those weekdays.
    @ColumnInfo(name = "days_of_week_mask") val daysOfWeekMask: Int,
    @ColumnInfo(name = "date_epoch_day") val dateEpochDay: Long? = null,
    // User intent only — the list toggle. Never flipped by the system.
    val enabled: Boolean = true,
    // Execution memory — the *scheduled* instant (not delivery time) of the last
    // occurrence step 4 fired. Purpose: backward-clock-jump dedup. Bonus: for a
    // one-shot, non-null means completed, with no extra state column.
    @ColumnInfo(name = "last_fired_occurrence_utc_millis")
    val lastFiredOccurrenceUtcMillis: Long? = null,
)

@Entity(tableName = "timer_preset")
data class TimerPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Nullable: an unnamed preset displays as its formatted duration — no
    // forced text entry on a watch.
    val label: String? = null,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int,
)

data class ReminderWithTitle(
    @Embedded val reminder: Reminder,
    val taskTitle: String?,
) {
    val displayLabel: String get() = taskTitle ?: reminder.label.orEmpty()
}
