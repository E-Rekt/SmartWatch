package com.cztask

import com.cztask.data.db.Reminder
import com.cztask.data.db.ReminderDao
import com.cztask.data.db.ReminderWithTitle
import com.cztask.data.db.Task
import com.cztask.data.db.TaskDao
import com.cztask.data.export.JsonDump
import com.cztask.data.db.TimerPreset
import com.cztask.data.repo.ReminderRepository
import com.cztask.data.repo.TaskRepository
import com.cztask.data.time.TimeSource
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Validation must reject bad input BEFORE any DAO call — these fakes explode on
// contact, proving rejection happens at the repository boundary.

private object ExplodingTaskDao : TaskDao {
    override fun observeAll(): Flow<List<Task>> = emptyFlow()
    override suspend fun insert(task: Task): Long = error("insert reached")
    override suspend fun setDone(id: Long, done: Boolean) = error("unexpected")
    override suspend fun delete(id: Long) = error("unexpected")
    override suspend fun deleteDone() = error("unexpected")
}

private object ExplodingReminderDao : ReminderDao {
    override fun observeAllWithTitle(): Flow<List<ReminderWithTitle>> = emptyFlow()
    override suspend fun enabledOnce(): List<Reminder> = error("unexpected")
    override suspend fun byId(id: Long): Reminder? = error("unexpected")
    override suspend fun idsForTask(taskId: Long): List<Long> = error("unexpected")
    override suspend fun idsForDoneTasks(): List<Long> = error("unexpected")
    override suspend fun insert(r: Reminder): Long = error("insert reached")
    override suspend fun update(r: Reminder) = error("unexpected")
    override suspend fun setEnabled(id: Long, enabled: Boolean) = error("unexpected")
    override suspend fun markFired(id: Long, occ: Long) = error("unexpected")
    override suspend fun delete(id: Long) = error("unexpected")
}

private object FixedTime : TimeSource {
    override fun nowUtcMillis() = 1_800_000_000_000L
    override fun zone(): ZoneId = ZoneId.of("UTC")
    override fun elapsedRealtimeMillis() = 0L
}

class RepositoryValidationTest {

    private val tasks = TaskRepository(ExplodingTaskDao, ExplodingReminderDao, FixedTime)
    private val reminders = ReminderRepository(ExplodingReminderDao, FixedTime)

    @Test fun `blank task title rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { tasks.add("   ") }
        }
    }

    @Test fun `standalone reminder without label rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                reminders.addOneShot(taskId = null, label = " ", date = LocalDate.now(), at = LocalTime.NOON)
            }
        }
    }

    @Test fun `empty and overflowing day masks rejected`() {
        for (mask in intArrayOf(0, 128, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    reminders.addRepeating(taskId = null, label = "x", daysOfWeekMask = mask, at = LocalTime.NOON)
                }
            }
        }
    }
}

class JsonDumpTest {

    @Test fun `dump escapes and round-trips structure`() {
        val json = JsonDump.dump(
            tasks = listOf(Task(id = 1, title = "say \"hi\"\nback\\slash", done = false, createdAtUtcMillis = 5, source = 1)),
            reminders = listOf(Reminder(id = 2, taskId = 1, label = null, timeOfDayMinutes = 540, daysOfWeekMask = 127)),
            presets = listOf(TimerPreset(id = 3, label = null, durationSeconds = 300)),
        )
        assertTrue(json.contains("\"say \\\"hi\\\"\\nback\\\\slash\""))
        assertTrue(json.contains("\"task_id\":1"))
        assertTrue(json.contains("\"label\":null"))
        assertTrue(json.contains("\"duration_seconds\":300"))
        // crude structural sanity: balanced braces/brackets
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
    }
}
