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

    @Query("SELECT * FROM task WHERE id = :id") suspend fun byId(id: Long): Task?

    /** Featured-task fallback when nothing is pinned. */
    @Query("SELECT * FROM task WHERE done = 0 ORDER BY created_at_utc_millis ASC LIMIT 1")
    suspend fun oldestOpen(): Task?

    /** NOT NOW: re-stamp sends the task to the back of the oldest-open queue. */
    @Query("UPDATE task SET created_at_utc_millis = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)
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

    /** Firing consumes any pending snooze along with stamping the dedup floor. */
    @Query("UPDATE reminder SET last_fired_occurrence_utc_millis = :occ, snooze_until_utc_millis = NULL WHERE id = :id")
    suspend fun markFired(id: Long, occ: Long)

    @Query("UPDATE reminder SET snooze_until_utc_millis = :until WHERE id = :id")
    suspend fun snoozeUntil(id: Long, until: Long)

    @Query("DELETE FROM reminder WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface FocusSessionDao {
    @Insert suspend fun insert(s: FocusSession): Long

    @Query("UPDATE focus_session SET ended_utc_millis = :ended, outcome = :outcome, extended_count = :extended WHERE id = :id")
    suspend fun finish(id: Long, ended: Long, outcome: Int, extended: Int)
}

@Dao
abstract class DailyTallyDao {
    @Query("SELECT * FROM daily_tally WHERE epoch_day = :day")
    abstract suspend fun forDay(day: Long): DailyTally?

    /** Last 7 days incl. today, newest first — the emerald streak window. */
    @Query("SELECT * FROM daily_tally WHERE epoch_day > :day - 7 AND epoch_day <= :day ORDER BY epoch_day DESC")
    abstract suspend fun lastWeek(day: Long): List<DailyTally>

    @Query("UPDATE daily_tally SET acts_completed = acts_completed + :acts, tasks_done = tasks_done + :tasks, rings = rings + :rings WHERE epoch_day = :day")
    protected abstract suspend fun bump(day: Long, acts: Int, tasks: Int, rings: Int): Int

    @Insert
    protected abstract suspend fun insert(t: DailyTally)

    /** SQLite 3.22 house rule: no UPSERT (3.24). UPDATE-then-INSERT in a
     *  transaction is the portable equivalent. */
    @androidx.room.Transaction
    open suspend fun add(day: Long, acts: Int = 0, tasks: Int = 0, rings: Int = 0) {
        if (bump(day, acts, tasks, rings) == 0) {
            insert(DailyTally(day, acts, tasks, rings))
        }
    }
}

@Dao
interface TimerPresetDao {
    @Query("SELECT * FROM timer_preset ORDER BY id")
    fun observeAll(): Flow<List<TimerPreset>>

    @Insert suspend fun insert(p: TimerPreset): Long
    @Query("DELETE FROM timer_preset WHERE id = :id") suspend fun delete(id: Long)
}
