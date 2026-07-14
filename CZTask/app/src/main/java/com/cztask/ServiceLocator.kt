package com.cztask

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.cztask.alarm.ReminderScheduler
import com.cztask.data.db.AppDatabase
import com.cztask.data.db.SeedCallback
import com.cztask.data.repo.ReminderRepository
import com.cztask.data.repo.TaskRepository
import com.cztask.data.repo.TimerPresetRepository
import com.cztask.data.time.ClockGuard
import com.cztask.data.time.ClockStatus
import com.cztask.data.time.LongStore
import com.cztask.data.time.SystemTimeSource
import com.cztask.data.time.TimerStateStore
import com.cztask.data.time.systemBootCount
import java.io.File

/** Manual DI. Hilt buys nothing at this scale and costs dex weight + startup
 *  time on a lowRamDevice. Everything is lazy off a single init(context). */
object ServiceLocator {

    private lateinit var appContext: Context

    /** Process-lifetime scope for write+reconcile pipelines. NOT lifecycleScope:
     *  a swipe-dismissed activity must not cancel the reconcile that re-arms OS
     *  alarms after a DB write has already committed. */
    val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    @Volatile var lastClockStatus: ClockStatus = ClockStatus.OK
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        lastClockStatus = clockGuard.checkAndAdvance()
    }

    val db: AppDatabase by lazy {
        // Pre-open escape hatch: keep one backup copy of the previous DB. If a
        // future migration ever fails, the data is one adb pull away instead of
        // gone. Under WAL, committed-but-uncheckpointed transactions live in
        // the -wal sidecar (and this process dies by SIGKILL, so it's rarely
        // empty) — the sidecar must travel with the main file or the backup is
        // a stale checkpoint. Named .bak/.bak-wal so sqlite replays it on open.
        // A stale orphaned .bak-wal is deleted, never left to be mis-replayed.
        // Safe: runs pre-open, no live connections, both files static.
        val dbFile = appContext.getDatabasePath(DB_NAME)
        if (dbFile.exists()) {
            runCatching {
                for (suffix in listOf("", "-wal")) {
                    val src = File(dbFile.path + suffix)
                    val dst = File(dbFile.parentFile, "$DB_NAME.bak$suffix")
                    if (src.exists()) src.copyTo(dst, overwrite = true) else dst.delete()
                }
            }
        }
        Room.databaseBuilder(appContext, AppDatabase::class.java, DB_NAME)
            .addCallback(SeedCallback)
            // WAL over the lowRam TRUNCATE heuristic: measured-slow eMMC makes
            // per-commit fsync the dominant write cost; WAL amortizes it.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // NO fallbackToDestructiveMigration(), ever: re-entering data on a
            // watch is painful. Every schema change from v2 on ships a Migration.
            .build()
    }

    private val time = SystemTimeSource

    val taskRepository: TaskRepository by lazy {
        TaskRepository(db.taskDao(), db.reminderDao(), time)
    }
    val reminderRepository: ReminderRepository by lazy {
        ReminderRepository(db.reminderDao(), time)
    }
    val timerPresetRepository: TimerPresetRepository by lazy {
        TimerPresetRepository(db.timerPresetDao())
    }
    val timerStateStore: TimerStateStore by lazy { TimerStateStore(appContext) }

    val reminderScheduler: ReminderScheduler by lazy {
        ReminderScheduler(reminderRepository, db.taskDao())
    }

    val clockGuard: ClockGuard by lazy {
        val sp = appContext.getSharedPreferences("clock_guard", Context.MODE_PRIVATE)
        ClockGuard(
            store = object : LongStore {
                override fun get(key: String, def: Long) = sp.getLong(key, def)
                override fun put(key: String, value: Long) {
                    sp.edit().putLong(key, value).apply()
                }
            },
            time = time,
            buildFloorUtcMillis = BuildConfig.BUILD_FLOOR_UTC_MILLIS,
            bootCount = { systemBootCount(appContext) },
        )
    }

    private const val DB_NAME = "cztask.db"
}
