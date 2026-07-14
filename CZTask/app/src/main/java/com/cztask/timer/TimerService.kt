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
import com.cztask.alarm.AlertBridge
import com.cztask.alarm.Notifications
import com.cztask.alarm.ReminderScheduler
import com.cztask.contract.Ids
import com.cztask.data.db.FocusSession
import com.cztask.data.time.TimerStateStore
import com.cztask.data.time.systemBootCount
import kotlinx.coroutines.launch

/**
 * Focus Act engine on the ELAPSED axis. Phase B lifecycle:
 *
 *   RUN --(T-5, planned>=10min)--> WARN buzz (landing gear, not an alarm bell)
 *       --(zero)--> act complete: session stamped, tally paid, TIME UP alert,
 *                   store enters OVERRUN
 *   OVERRUN --(+10 min)--> SURFACE? prompt (+5 EXTEND / DONE)
 *           --(+25 min)--> one escalation, then silence (never faster/louder)
 *
 * Truth lives in exact ELAPSED_REALTIME_WAKEUP alarms + TimerStateStore —
 * the in-service Handler tick is best-effort UX only (measured: this device
 * kills processes in 30-60 s). EXTEND (+5) works pre- and post-zero.
 */
class TimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var endElapsed = 0L
    private var taskLabel: String? = null

    private fun statusText(leftMs: Long): String =
        taskLabel?.let { "${format(leftMs)} · ${it.take(16).uppercase()}" } ?: format(leftMs)

    private val tick = object : Runnable {
        override fun run() {
            val left = endElapsed - SystemClock.elapsedRealtime()
            if (left <= 0) {
                completeAct()
            } else {
                getSystemService(android.app.NotificationManager::class.java)
                    .notify(Ids.NOTIF_TIMER_RUNNING, Notifications.timerRunning(this@TimerService, statusText(left)))
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAct(intent)
            ACTION_EXTEND -> extendAct()
            ACTION_ACK_DONE -> ackDone()
            ACTION_CANCEL -> cancelAct()
            ACTION_COMPLETE -> completeAct()   // backup-alarm path (dead process)
        }
        return START_NOT_STICKY
    }

    private fun startAct(intent: Intent) {
        val durationSec = intent.getIntExtra(EXTRA_DURATION_SECONDS, 0)
        if (durationSec <= 0) { stopSelf(); return }
        endElapsed = SystemClock.elapsedRealtime() + durationSec * 1000L
        taskLabel = intent.getStringExtra(EXTRA_TASK_LABEL)
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val startedWall = System.currentTimeMillis()

        // Durable session row first, then the SP record that references it.
        ServiceLocator.appScope.launch {
            val sessionId = ServiceLocator.db.focusSessionDao().insert(
                FocusSession(
                    taskId = taskId.takeIf { it >= 0 },
                    plannedSeconds = durationSec,
                    startedUtcMillis = startedWall,
                )
            )
            ServiceLocator.timerStateStore.save(
                TimerStateStore.RunningTimer(
                    presetId = intent.getLongExtra(EXTRA_PRESET_ID, -1L),
                    endElapsedRealtimeMillis = endElapsed,
                    bootCount = systemBootCount(this@TimerService),
                    startedWallUtcMillis = startedWall,
                    taskId = taskId,
                    label = taskLabel,
                    plannedSeconds = durationSec,
                    sessionId = sessionId,
                )
            )
        }

        armBoundaries(durationSec * 1000L)
        startForeground(Ids.NOTIF_TIMER_RUNNING, Notifications.timerRunning(this, statusText(durationSec * 1000L)))
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
    }

    private fun armBoundaries(leftMs: Long) {
        val am = getSystemService(AlarmManager::class.java)
        // Completion is truth via the backup alarm; WARN only when the act is
        // long enough that a T-5 warning means something.
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, endElapsed, backupAlarm(this))
        if (leftMs >= 10 * 60_000L) {
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, endElapsed - 5 * 60_000L, warnAlarm(this))
        } else {
            am.cancel(warnAlarm(this))
        }
    }

    /** +5 EXTEND — the blind "keep going" gesture, pre- or post-zero. */
    private fun extendAct() {
        val store = ServiceLocator.timerStateStore
        val t = (store.recover(systemBootCount(this)) as? TimerStateStore.Recovery.Running)?.timer
            ?: run { stopSelf(); return }
        val newEnd = maxOf(t.endElapsedRealtimeMillis, SystemClock.elapsedRealtime()) + 5 * 60_000L
        endElapsed = newEnd
        taskLabel = t.label
        store.save(
            t.copy(
                endElapsedRealtimeMillis = newEnd,
                phase = TimerStateStore.PHASE_RUN,
                extendedCount = t.extendedCount + 1,
            )
        )
        getSystemService(AlarmManager::class.java).cancel(overrunAlarm(this))
        Notifications.cancel(this, Ids.NOTIF_ACT_STATUS)
        armBoundaries(newEnd - SystemClock.elapsedRealtime())
        startForeground(Ids.NOTIF_TIMER_RUNNING, Notifications.timerRunning(this, statusText(newEnd - SystemClock.elapsedRealtime())))
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
    }

    /** Zero while RUN: the act is complete — reward, then surface-watch. */
    private fun completeAct() {
        val store = ServiceLocator.timerStateStore
        val t = (store.recover(systemBootCount(this)) as? TimerStateStore.Recovery.Running)?.timer
        if (t == null || t.phase != TimerStateStore.PHASE_RUN) { stopEverything(); return }

        ServiceLocator.appScope.launch {
            if (t.sessionId >= 0) {
                ServiceLocator.db.focusSessionDao().finish(
                    t.sessionId, System.currentTimeMillis(),
                    FocusSession.OUTCOME_DONE, t.extendedCount,
                )
            }
            val today = java.time.LocalDate.now().toEpochDay()
            ServiceLocator.db.dailyTallyDao().add(today, acts = 1, rings = 1)
        }

        store.save(t.copy(phase = TimerStateStore.PHASE_OVERRUN))
        Notifications.postTimerDone(this)
        AlertBridge.showTimeUp(this, t.label)
        // Hyperfocus watch: first SURFACE? at +10 min (receiver arms the +25
        // escalation). The process may die before then — that's the point.
        getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            t.endElapsedRealtimeMillis + 10 * 60_000L,
            overrunAlarm(this),
        )
        stopEverything()
    }

    /** DONE pill: fully close the act (post-zero or any time). */
    private fun ackDone() {
        val store = ServiceLocator.timerStateStore
        val t = (store.recover(systemBootCount(this)) as? TimerStateStore.Recovery.Running)?.timer
        store.clear()
        cancelAllBoundaries()
        if (t != null && t.phase == TimerStateStore.PHASE_RUN && t.sessionId >= 0) {
            // Acknowledged done BEFORE zero counts as completion too.
            ServiceLocator.appScope.launch {
                ServiceLocator.db.focusSessionDao().finish(
                    t.sessionId, System.currentTimeMillis(),
                    FocusSession.OUTCOME_DONE, t.extendedCount,
                )
                ServiceLocator.db.dailyTallyDao().add(java.time.LocalDate.now().toEpochDay(), acts = 1, rings = 1)
            }
        }
        Notifications.cancel(this, Ids.NOTIF_ACT_STATUS)
        Notifications.cancel(this, Ids.NOTIF_TIMER_DONE)
        stopEverything()
    }

    /** Bail pre-zero: quiet, logged, never shamed. */
    private fun cancelAct() {
        val store = ServiceLocator.timerStateStore
        val t = (store.recover(systemBootCount(this)) as? TimerStateStore.Recovery.Running)?.timer
        store.clear()
        cancelAllBoundaries()
        if (t != null && t.phase == TimerStateStore.PHASE_RUN && t.sessionId >= 0) {
            ServiceLocator.appScope.launch {
                ServiceLocator.db.focusSessionDao().finish(
                    t.sessionId, System.currentTimeMillis(),
                    FocusSession.OUTCOME_BAILED, t.extendedCount,
                )
            }
        }
        Notifications.cancel(this, Ids.NOTIF_ACT_STATUS)
        stopEverything()
    }

    private fun cancelAllBoundaries() {
        val am = getSystemService(AlarmManager::class.java)
        am.cancel(backupAlarm(this))
        am.cancel(warnAlarm(this))
        am.cancel(overrunAlarm(this))
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

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.cztask.timer.START"
        const val ACTION_CANCEL = "com.cztask.timer.CANCEL"
        const val ACTION_EXTEND = "com.cztask.timer.EXTEND"
        const val ACTION_ACK_DONE = "com.cztask.timer.ACK_DONE"
        const val ACTION_COMPLETE = "com.cztask.timer.COMPLETE"
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

        fun cancel(context: Context) = send(context, ACTION_CANCEL)
        fun extend(context: Context) = send(context, ACTION_EXTEND)
        fun ackDone(context: Context) = send(context, ACTION_ACK_DONE)

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, TimerService::class.java).setAction(action))
        }

        fun backupAlarm(context: Context) =
            ReminderScheduler.broadcast(context, Ids.RC_TIMER_ELAPSED_ALARM, AlarmReceiver.ACTION_TIMER_FIRE)

        fun warnAlarm(context: Context) =
            ReminderScheduler.broadcast(context, Ids.RC_ACT_WARN, AlarmReceiver.ACTION_ACT_WARN)

        fun overrunAlarm(context: Context) =
            ReminderScheduler.broadcast(context, Ids.RC_ACT_OVERRUN, AlarmReceiver.ACTION_ACT_OVERRUN)

        fun format(ms: Long): String {
            val totalSec = (ms + 999) / 1000
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
    }
}
