package com.cztask

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cztask.data.db.AppDatabase
import com.cztask.data.db.Reminder
import com.cztask.data.db.SeedCallback
import com.cztask.data.db.Task
import com.cztask.data.repo.ReminderRepository
import com.cztask.data.repo.TaskRepository
import com.cztask.data.time.TimeSource
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tier 2: what only the device can prove — real SQLite 3.22 behavior, FK
 *  pragma, InvalidationTracker, seeding — through the real repository path. */
@RunWith(AndroidJUnit4::class)
class DataLayerTest {

    private lateinit var db: AppDatabase

    // Pinned fake time: Monday 2026-07-13 12:00 in the device's zone.
    private val zone: ZoneId = ZoneId.systemDefault()
    private val monday: LocalDate = LocalDate.of(2026, 7, 13)
    private fun at(date: LocalDate, time: LocalTime): Long =
        ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()

    private val fixedTime = object : TimeSource {
        override fun nowUtcMillis() = at(monday, LocalTime.NOON)
        override fun zone(): ZoneId = zone
        override fun elapsedRealtimeMillis() = 0L
    }

    @Before fun open() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .addCallback(SeedCallback)
            .build()
    }

    @After fun close() { db.close() }

    @Test fun t1_seedPresetsExistInIdOrder() = runTest {
        val presets = db.timerPresetDao().observeAll().first()
        assertEquals(3, presets.size)
        assertEquals(listOf("Pomodoro", "Break", null), presets.map { it.label })
        assertEquals(listOf(1500, 300, 600), presets.map { it.durationSeconds })
    }

    @Test fun t2_taskOrderingOpenNewestFirstDoneSink() = runTest {
        val dao = db.taskDao()
        dao.insert(Task(title = "old open", createdAtUtcMillis = 100))
        dao.insert(Task(title = "new open", createdAtUtcMillis = 200))
        val doneId = dao.insert(Task(title = "done", createdAtUtcMillis = 300))
        dao.setDone(doneId, true)
        assertEquals(listOf("new open", "old open", "done"),
            dao.observeAll().first().map { it.title })
    }

    @Test fun t3_cascadeDeletesLinkedNotStandalone() = runTest {
        val tasks = TaskRepository(db.taskDao(), db.reminderDao(), fixedTime)
        val taskId = tasks.add("with reminder")
        val linked = db.reminderDao().insert(
            Reminder(taskId = taskId, timeOfDayMinutes = 540, daysOfWeekMask = 127))
        db.reminderDao().insert(
            Reminder(label = "standalone", timeOfDayMinutes = 600, daysOfWeekMask = 127))

        val orphaned = tasks.delete(taskId)

        assertEquals(listOf(linked), orphaned)
        val remaining = db.reminderDao().observeAllWithTitle().first()
        assertEquals(1, remaining.size)
        assertEquals("standalone", remaining[0].displayLabel)
    }

    @Test fun t4_leftJoinTitleProjection() = runTest {
        val taskId = db.taskDao().insert(Task(title = "the task", createdAtUtcMillis = 1))
        db.reminderDao().insert(Reminder(taskId = taskId, timeOfDayMinutes = 540, daysOfWeekMask = 127))
        db.reminderDao().insert(Reminder(label = "loner", timeOfDayMinutes = 600, daysOfWeekMask = 127))
        val rows = db.reminderDao().observeAllWithTitle().first()
        assertEquals("the task", rows[0].displayLabel)
        assertEquals("loner", rows[1].displayLabel)
        assertNull(rows[1].taskTitle)
    }

    @Test fun t5_invalidationTrackerEmitsOnInsert() = runBlocking {
        // ONE sustained collection: the second emission can only come from the
        // InvalidationTracker (a fresh .first() would just re-run the query).
        // runBlocking, not runTest: emissions arrive on Room's real executor
        // and virtual-time timeouts would fire before real work completes.
        val dao = db.taskDao()
        val emissions = Channel<List<Task>>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) { dao.observeAll().collect { emissions.send(it) } }
        assertEquals(0, withTimeout(10_000) { emissions.receive() }.size)
        dao.insert(Task(title = "x", createdAtUtcMillis = 1))
        assertEquals(1, withTimeout(10_000) { emissions.receive() }.size)
        job.cancel()
    }

    @Test fun t6_schedulePlanFullPath() = runTest {
        val repo = ReminderRepository(db.reminderDao(), fixedTime)
        // Overdue one-shot: yesterday 08:00, never fired.
        val overdueId = repo.addOneShot(null, "overdue", monday.minusDays(1), LocalTime.of(8, 0))

        var plan = repo.schedulePlan()
        assertEquals(listOf(overdueId), plan.dueNow.map { it.reminder.id })
        assertEquals(at(monday.minusDays(1), LocalTime.of(8, 0)), plan.dueNow[0].occurrenceUtcMillis)
        assertNull(plan.nextFireAtUtcMillis)

        // markFired makes the dedup durable through the real DB.
        repo.markFired(overdueId, plan.dueNow[0].occurrenceUtcMillis)
        plan = repo.schedulePlan()
        assertTrue(plan.dueNow.isEmpty())

        // Daily 08:00 arms tomorrow (now is noon).
        repo.addRepeating(null, "daily", 0b1111111, LocalTime.of(8, 0))
        plan = repo.schedulePlan()
        assertEquals(at(monday.plusDays(1), LocalTime.of(8, 0)), plan.nextFireAtUtcMillis)

        // Late repeating within grace (11:45, now noon): due once, with the
        // scheduled instant; markFired retires it through the real DB.
        val lateId = repo.addRepeating(null, "late", 0b1111111, LocalTime.of(11, 45))
        plan = repo.schedulePlan()
        assertEquals(listOf(lateId), plan.dueNow.map { it.reminder.id })
        assertEquals(at(monday, LocalTime.of(11, 45)), plan.dueNow[0].occurrenceUtcMillis)
        repo.markFired(lateId, plan.dueNow[0].occurrenceUtcMillis)
        assertTrue(repo.schedulePlan().dueNow.isEmpty())
    }

    @Test fun t7_coalescedAlarmSharedInstant() = runTest {
        val repo = ReminderRepository(db.reminderDao(), fixedTime)
        val a = repo.addRepeating(null, "a", 0b1111111, LocalTime.of(8, 0))
        val b = repo.addRepeating(null, "b", 0b1111111, LocalTime.of(8, 0))
        repo.addRepeating(null, "later", 0b1111111, LocalTime.of(9, 0))
        val plan = repo.schedulePlan()
        assertEquals(setOf(a, b), plan.reminderIdsAtNextFire.toSet())
    }

    @Test fun t8_doneTaskSuppressesItsReminders() = runTest {
        val tasks = TaskRepository(db.taskDao(), db.reminderDao(), fixedTime)
        val repo = ReminderRepository(db.reminderDao(), fixedTime)
        val taskId = tasks.add("trash")
        db.reminderDao().insert(Reminder(taskId = taskId, timeOfDayMinutes = 480, daysOfWeekMask = 127))
        repo.addRepeating(null, "unrelated", 0b1111111, LocalTime.of(8, 0))

        assertEquals(2, db.reminderDao().enabledOnce().size)
        val affected = tasks.setDone(taskId, true)
        assertEquals(1, affected.size)
        // The done task's reminder vanishes from scheduling; standalone stays.
        assertEquals(listOf("unrelated"),
            db.reminderDao().enabledOnce().map { it.label })
    }
}
