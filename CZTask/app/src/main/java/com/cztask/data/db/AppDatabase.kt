package com.cztask.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Task::class, Reminder::class, TimerPreset::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun reminderDao(): ReminderDao
    abstract fun timerPresetDao(): TimerPresetDao
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
