package com.cztask.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Task::class, Reminder::class, TimerPreset::class, FocusSession::class, DailyTally::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun reminderDao(): ReminderDao
    abstract fun timerPresetDao(): TimerPresetDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun dailyTallyDao(): DailyTallyDao
}

/** Migration 2 (Phase B). No destructive fallback exists in this project —
 *  this DDL must match the Room-generated schema exactly (Room validates at
 *  open) and must run on the device's SQLite 3.22. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reminder ADD COLUMN prealert_minutes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE reminder ADD COLUMN default_duration_seconds INTEGER")
        db.execSQL("ALTER TABLE reminder ADD COLUMN snooze_until_utc_millis INTEGER")
        db.execSQL("ALTER TABLE reminder ADD COLUMN launch_mode INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `focus_session` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`task_id` INTEGER, " +
                "`planned_seconds` INTEGER NOT NULL, " +
                "`started_utc_millis` INTEGER NOT NULL, " +
                "`ended_utc_millis` INTEGER, " +
                "`outcome` INTEGER NOT NULL DEFAULT 0, " +
                "`extended_count` INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY(`task_id`) REFERENCES `task`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_focus_session_task_id` ON `focus_session` (`task_id`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `daily_tally` (" +
                "`epoch_day` INTEGER PRIMARY KEY NOT NULL, " +
                "`acts_completed` INTEGER NOT NULL DEFAULT 0, " +
                "`tasks_done` INTEGER NOT NULL DEFAULT 0, " +
                "`rings` INTEGER NOT NULL DEFAULT 0)"
        )
    }
}

/** First-run seed so the timer screen isn't empty. Runs inside onCreate's
 *  transaction; SQL must stay valid on the device's SQLite 3.22. */
object SeedCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO timer_preset (label, duration_seconds) VALUES " +
                "('Pomodoro', 1500), ('Break', 300), (NULL, 600)"
        )
    }

    // WAL + synchronous=NORMAL: on this slow eMMC a TRUNCATE-mode fsync per
    // commit is the dominant write cost; WAL amortizes it and NORMAL is safe
    // under WAL (app-level data loss window is one checkpoint, acceptable for
    // this dataset). Applied in onOpen so it covers every open, not just create.
    override fun onOpen(db: SupportSQLiteDatabase) {
        // moveToFirst() forces statement execution — rawQuery compiles lazily,
        // and an unstepped PRAGMA assignment may never run.
        db.query("PRAGMA synchronous = NORMAL").use { it.moveToFirst() }
    }
}
