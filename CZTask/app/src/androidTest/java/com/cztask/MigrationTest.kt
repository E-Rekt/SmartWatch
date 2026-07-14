package com.cztask

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cztask.data.db.AppDatabase
import com.cztask.data.db.MIGRATION_1_2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration 1->2 against the DEVICE's SQLite (3.22) — the dev machine's newer
 * SQLite proves nothing, and this project has no destructive fallback: a bad
 * migration is a permanent crash loop holding the user's data hostage.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_preservesDataAndAddsSchema() {
        // Seed a realistic v1 database.
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO task (title, done, created_at_utc_millis, source) VALUES ('keep me', 0, 42, 0)")
            execSQL(
                "INSERT INTO reminder (task_id, label, time_of_day_minutes, days_of_week_mask, " +
                    "date_epoch_day, enabled, last_fired_occurrence_utc_millis) " +
                    "VALUES (NULL, 'pills', 540, 127, NULL, 1, NULL)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2)

        // v1 data intact, v2 defaults applied.
        db.query("SELECT title FROM task").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("keep me", c.getString(0))
        }
        db.query("SELECT prealert_minutes, launch_mode, snooze_until_utc_millis FROM reminder").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertEquals(0, c.getInt(1))
            assertTrue(c.isNull(2))
        }
        // New tables exist and accept rows on this device's SQLite.
        db.execSQL("INSERT INTO focus_session (task_id, planned_seconds, started_utc_millis) VALUES (NULL, 1500, 1)")
        db.execSQL("INSERT INTO daily_tally (epoch_day, acts_completed, tasks_done, rings) VALUES (1, 1, 0, 1)")
        db.query("SELECT COUNT(*) FROM focus_session").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
        }
    }

    private companion object { const val DB = "migration-test.db" }
}
