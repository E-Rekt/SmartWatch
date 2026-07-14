package com.cztask.timer

import android.app.AlarmManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.cztask.ServiceLocator
import com.cztask.alarm.AlarmReceiver
import com.cztask.alarm.Notifications
import com.cztask.alarm.ReminderScheduler
import com.cztask.contract.Ids
import com.cztask.data.time.TimerStateStore
import com.cztask.data.time.systemBootCount

/**
 * Countdown engine. Lives entirely on the ELAPSED axis — immune to wall-clock
 * changes by construction. Three layers of survivability, in order:
 *   1. this foreground service ticking the notification once a second,
 *   2. an exact ELAPSED_REALTIME_WAKEUP backup alarm that posts completion even
 *      if the process is killed (measured: this device kills apps in 30-60 s),
 *   3. TimerStateStore (SharedPreferences, boot-count-anchored) as durable
 *      truth — a reboot cancels the timer and reports it once.
 */
class TimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var endElapsed = 0L
    private var taskLabel: String? = null

    /** "12:34 · WRITE REPORT" when the act is bound to a task. */
    private fun statusText(leftMs: Long): String =
        taskLabel?.let { "${format(leftMs)} · ${it.take(16).uppercase()}" } ?: format(leftMs)

    private val tick = object : Runnable {
        override fun run() {
            val left = endElapsed - SystemClock.elapsedRealtime()
            if (left <= 0) {
                finishTimer()
            } else {
                getSystemService(android.app.NotificationManager::class.java)
                    .notify(Ids.NOTIF_TIMER_RUNNING, Notifications.timerRunning(this@TimerService, statusText(left)))
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val durationSec = intent.getIntExtra(EXTRA_DURATION_SECONDS, 0)
                val durationMs = durationSec * 1000L
                if (durationMs <= 0) { stopSelf(); return START_NOT_STICKY }
                endElapsed = SystemClock.elapsedRealtime() + durationMs
                taskLabel = intent.getStringExtra(EXTRA_TASK_LABEL)
                ServiceLocator.timerStateStore.save(
                    TimerStateStore.RunningTimer(
                        presetId = intent.getLongExtra(EXTRA_PRESET_ID, -1L),
                        endElapsedRealtimeMillis = endElapsed,
                        bootCount = systemBootCount(this),
                        startedWallUtcMillis = System.currentTimeMillis(),
                        taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L),
                        label = taskLabel,
                        plannedSeconds = durationSec,
                    )
                )
                // Accepted limitation: allowWhileIdle shares a per-app dispatch
                // budget (~9 min) in doze — if a reminder dispatched just before
                // expiry while dozing, this backup can be minutes late. The
                // primary path (this service's tick) is unaffected; revisit with
                // setAlarmClock() if late buzzes are ever observed in practice.
                getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, endElapsed, backupAlarm())
                startForeground(Ids.NOTIF_TIMER_RUNNING, Notifications.timerRunning(this, statusText(durationMs)))
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, 1000)
            }
            ACTION_CANCEL -> {
                ServiceLocator.timerStateStore.clear()
                getSystemService(AlarmManager::class.java).cancel(backupAlarm())
                stopEverything()
            }
        }
        return START_NOT_STICKY
    }

    private fun finishTimer() {
        // The final tick and the backup alarm land on the main looper at
        // essentially the same instant; whichever runs second must not re-post
        // (a second notify() = a second buzz). Check-then-clear is atomic here
        // because both contenders run on this looper.
        val store = ServiceLocator.timerStateStore
        if (store.recover(systemBootCount(this)) is TimerStateStore.Recovery.Running) {
            store.clear()
            Notifications.postTimerDone(this)
        }
        getSystemService(AlarmManager::class.java).cancel(backupAlarm())
        stopEverything()
    }

    private fun stopEverything() {
        handler.removeCallbacks(tick)
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun backupAlarm() =
        ReminderScheduler.broadcast(this, Ids.RC_TIMER_ELAPSED_ALARM, AlarmReceiver.ACTION_TIMER_FIRE)

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.cztask.timer.START"
        const val ACTION_CANCEL = "com.cztask.timer.CANCEL"
        const val EXTRA_DURATION_SECONDS = "duration_seconds"
        const val EXTRA_PRESET_ID = "preset_id"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_LABEL = "task_label"

        fun start(
            context: Context,
            presetId: Long,
            durationSeconds: Int,
            taskId: Long = -1L,
            taskLabel: String? = null,
        ) {
            context.startService(
                Intent(context, TimerService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PRESET_ID, presetId)
                    .putExtra(EXTRA_DURATION_SECONDS, durationSeconds)
                    .putExtra(EXTRA_TASK_ID, taskId)
                    .putExtra(EXTRA_TASK_LABEL, taskLabel)
            )
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, TimerService::class.java).setAction(ACTION_CANCEL)
            )
        }

        fun format(ms: Long): String {
            val totalSec = (ms + 999) / 1000
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
    }
}
