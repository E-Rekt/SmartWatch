package com.cztask.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// House rule: device SQLite is 3.22 (API 28) — no UPSERT (3.24), no window
// functions (3.25). Anything newer works on the dev machine and explodes on
// the watch; instrumented tests are the enforcement.

@Dao
interface TaskDao {
    @Query("SELECT * FROM task ORDER BY done ASC, created_at_utc_millis DESC")
    fun observeAll(): Flow<List<Task>>

    @Insert suspend fun insert(task: Task): Long

    @Query("UPDATE task SET done = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)

    @Query("DELETE FROM task WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM task WHERE done = 1") suspend fun deleteDone()

    /** Notification content for a task-linked reminder. */
    @Query("SELECT title FROM task WHERE id = :id") suspend fun title(id: Long): String?

    /** Watch face / tile badge. */
    @Query("SELECT COUNT(*) FROM task WHERE done = 0") suspend fun openCount(): Int
}

@Dao
interface ReminderDao {
    @Query(
        """SELECT reminder.*, task.title AS taskTitle
           FROM reminder LEFT JOIN task ON task.id = reminder.task_id
           ORDER BY reminder.id"""
    )
    fun observeAllWithTitle(): Flow<List<ReminderWithTitle>>

    /** Scheduler input. A reminder linked to a DONE task is suppressed (its
     *  purpose is finished); standalone reminders (task_id NULL) pass. */
    @Query(
        """SELECT reminder.* FROM reminder
           LEFT JOIN task ON task.id = reminder.task_id
           WHERE reminder.enabled = 1 AND (task.done IS NULL OR task.done = 0)"""
    )
    suspend fun enabledOnce(): List<Reminder>

    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun byId(id: Long): Reminder?

    /** Step 4: cancel alarms/notifications before a cascade destroys the evidence. */
    @Query("SELECT id FROM reminder WHERE task_id = :taskId")
    suspend fun idsForTask(taskId: Long): List<Long>

    @Query(
        """SELECT reminder.id FROM reminder
           JOIN task ON task.id = reminder.task_id WHERE task.done = 1"""
    )
    suspend fun idsForDoneTasks(): List<Long>

    @Insert suspend fun insert(r: Reminder): Long
    @Update suspend fun update(r: Reminder)

    @Query("UPDATE reminder SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE reminder SET last_fired_occurrence_utc_millis = :occ WHERE id = :id")
    suspend fun markFired(id: Long, occ: Long)

    @Query("DELETE FROM reminder WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface TimerPresetDao {
    @Query("SELECT * FROM timer_preset ORDER BY id")
    fun observeAll(): Flow<List<TimerPreset>>

    @Insert suspend fun insert(p: TimerPreset): Long
    @Query("DELETE FROM timer_preset WHERE id = :id") suspend fun delete(id: Long)
}
