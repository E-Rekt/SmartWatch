package com.cztask.data.repo

import com.cztask.data.db.Reminder
import com.cztask.data.db.ReminderDao
import com.cztask.data.db.ReminderWithTitle
import com.cztask.data.db.Task
import com.cztask.data.db.TaskDao
import com.cztask.data.db.TimerPreset
import com.cztask.data.db.TimerPresetDao
import com.cztask.data.time.NextFireCalculator
import com.cztask.data.time.TimeSource
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Repositories are the seam step 4 needs: OS alarms are external state, so any
// DB write that can invalidate an alarm must flow through here (never
// DAO-direct from UI), and mutations that kill reminders return the worklist
// of affected ids BEFORE the evidence is destroyed.

class TaskRepository(
    private val tasks: TaskDao,
    private val reminders: ReminderDao,
    private val time: TimeSource,
    private val pins: com.cztask.data.time.PinStore? = null,
) {
    fun observeAll(): Flow<List<Task>> = tasks.observeAll()

    /** The ONE Thing, resolved: today's pin if it still points at an open
     *  task (stale/done/deleted pins self-clear), else the oldest open task.
     *  Second of the pair reports whether the task is actually pinned. */
    suspend fun featured(): Pair<Task?, Boolean> {
        val today = java.time.Instant.ofEpochMilli(time.nowUtcMillis())
            .atZone(time.zone()).toLocalDate().toEpochDay()
        pins?.pinnedId(today)?.let { id ->
            val t = tasks.byId(id)
            if (t != null && !t.done) return t to true
            pins.clear()
        }
        return tasks.oldestOpen() to false
    }

    suspend fun pin(taskId: Long) {
        val today = java.time.Instant.ofEpochMilli(time.nowUtcMillis())
            .atZone(time.zone()).toLocalDate().toEpochDay()
        pins?.pin(taskId, today)
    }

    fun unpin() = pins?.clear()

    /** NOT NOW: pinned task -> unpin; unpinned -> back of the queue. */
    suspend fun notNow(taskId: Long, wasPinned: Boolean) {
        if (wasPinned) pins?.clear() else tasks.touch(taskId, time.nowUtcMillis())
    }

    suspend fun add(title: String, source: Int = Task.SOURCE_MANUAL): Long {
        val t = title.trim()
        require(t.isNotEmpty()) { "blank task" }
        return tasks.insert(Task(title = t, createdAtUtcMillis = time.nowUtcMillis(), source = source))
    }

    /** Returns ids of this task's reminders. Completion suppresses them from
     *  scheduling (enabledOnce joins task.done); step 4 uses the ids to dismiss
     *  any currently-showing notification and re-derive the alarm plan. */
    suspend fun setDone(id: Long, done: Boolean): List<Long> {
        tasks.setDone(id, done)
        return reminders.idsForTask(id)
    }

    /** Returns ids of reminders removed by cascade — step 4 cancels their alarms. */
    suspend fun delete(id: Long): List<Long> {
        val orphaned = reminders.idsForTask(id)
        tasks.delete(id)
        return orphaned
    }

    /** Same contract for bulk clear. */
    suspend fun clearDone(): List<Long> {
        val orphaned = reminders.idsForDoneTasks()
        tasks.deleteDone()
        return orphaned
    }
}

/** Everything step 4's scheduler needs, as one idempotent derivation. Call on
 *  BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED, after any reminder mutation,
 *  and after each firing. */
data class SchedulePlan(
    /** Fire immediately: overdue one-shots (unbounded lateness) plus repeating
     *  occurrences missed by <= LATE_GRACE_MS, as (reminder, occurrence) so the
     *  firing path can stamp markFired with the scheduled instant. */
    val dueNow: List<DueReminder>,
    /** Arm one RTC_WAKEUP alarm at this instant (null = nothing to arm). */
    val nextFireAtUtcMillis: Long?,
    /** All reminders sharing that instant (coalesced alarm). */
    val reminderIdsAtNextFire: List<Long>,
) {
    data class DueReminder(val reminder: Reminder, val occurrenceUtcMillis: Long)
}

class ReminderRepository(
    private val dao: ReminderDao,
    private val time: TimeSource,
) {
    /** Sorted by computed next occurrence (in Kotlin, not SQL — derived value). */
    fun observeAll(): Flow<List<ReminderWithTitle>> = dao.observeAllWithTitle().map { list ->
        val now = time.nowUtcMillis()
        val zone = time.zone()
        list.sortedBy { NextFireCalculator.nextOccurrence(it.reminder, now, zone) ?: Long.MAX_VALUE }
    }

    suspend fun addOneShot(taskId: Long?, label: String?, date: LocalDate, at: LocalTime): Long {
        requireLabelOrTask(taskId, label)
        return dao.insert(
            Reminder(
                taskId = taskId, label = label?.trim(),
                timeOfDayMinutes = at.hour * 60 + at.minute,
                daysOfWeekMask = 0, dateEpochDay = date.toEpochDay(),
            )
        )
    }

    suspend fun addRepeating(taskId: Long?, label: String?, daysOfWeekMask: Int, at: LocalTime): Long {
        requireLabelOrTask(taskId, label)
        require(daysOfWeekMask in 1..127) { "empty or invalid day mask" }
        return dao.insert(
            Reminder(
                taskId = taskId, label = label?.trim(),
                timeOfDayMinutes = at.hour * 60 + at.minute,
                daysOfWeekMask = daysOfWeekMask,
            )
        )
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun byId(id: Long): Reminder? = dao.byId(id)

    /** Step 4 calls after delivering; occ = the SCHEDULED occurrence instant
     *  (from SchedulePlan/DueReminder), never the delivery wall time — the
     *  backward-jump dedup depends on that. */
    suspend fun markFired(id: Long, occurrenceUtcMillis: Long) = dao.markFired(id, occurrenceUtcMillis)

    suspend fun schedulePlan(nowUtcMillis: Long = time.nowUtcMillis()): SchedulePlan {
        val zone = time.zone()
        val enabled = dao.enabledOnce()   // excludes reminders of done tasks

        val dueNow = buildList {
            for (r in enabled) {
                if (NextFireCalculator.isOverdueOneShot(r, nowUtcMillis, zone)) {
                    NextFireCalculator.oneShotOccurrence(r, zone)
                        ?.let { add(SchedulePlan.DueReminder(r, it)) }
                } else {
                    NextFireCalculator.lateRepeatingOccurrence(r, nowUtcMillis, zone)
                        ?.let { add(SchedulePlan.DueReminder(r, it)) }
                }
            }
        }

        val nexts = enabled.mapNotNull { r ->
            NextFireCalculator.nextOccurrence(r, nowUtcMillis, zone)?.let { r.id to it }
        }
        val nextAt = nexts.minOfOrNull { it.second }
        return SchedulePlan(dueNow, nextAt, nexts.filter { it.second == nextAt }.map { it.first })
    }

    private fun requireLabelOrTask(taskId: Long?, label: String?) =
        require(taskId != null || !label.isNullOrBlank()) { "standalone reminder needs a label" }
}

class TimerPresetRepository(private val dao: TimerPresetDao) {
    fun observeAll(): Flow<List<TimerPreset>> = dao.observeAll()

    suspend fun add(label: String?, durationSeconds: Int): Long {
        require(durationSeconds in 1..86_400) { "duration out of range" }
        return dao.insert(
            TimerPreset(label = label?.trim()?.ifEmpty { null }, durationSeconds = durationSeconds)
        )
    }

    suspend fun delete(id: Long) = dao.delete(id)
}
