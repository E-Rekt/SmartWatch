package com.cztask.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.cztask.ServiceLocator
import com.cztask.data.time.TimerStateStore
import com.cztask.data.time.systemBootCount
import com.cztask.timer.TimerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Terminus for our own PendingIntents: the coalesced reminder alarm, the
 *  daily reconcile backstop, and every Focus Act boundary (completion backup,
 *  T-5 warn, overrun surfacing). Boundaries are truth — the service's tick is
 *  best-effort UX on a device that kills processes in 30-60 s. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_REMINDER_ALARM, ACTION_DAILY_RECONCILE -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ServiceLocator.reminderScheduler.reconcile(app)
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_TIMER_FIRE -> {
                // Completion boundary into a possibly-dead process: route to
                // the service's single completion path (idempotent — a RUN
                // phase check guards double completion with the live tick).
                val rec = ServiceLocator.timerStateStore.recover(systemBootCount(app))
                if (rec is TimerStateStore.Recovery.Running &&
                    rec.timer.phase == TimerStateStore.PHASE_RUN &&
                    SystemClock.elapsedRealtime() >= rec.timer.endElapsedRealtimeMillis
                ) {
                    app.startService(
                        Intent(app, TimerService::class.java).setAction(TimerService.ACTION_COMPLETE)
                    )
                }
            }

            ACTION_ACT_WARN -> {
                // T-5 landing gear: one distinct buzz, no screen, no sound.
                val store = ServiceLocator.timerStateStore
                val rec = store.recover(systemBootCount(app))
                if (rec is TimerStateStore.Recovery.Running &&
                    rec.timer.phase == TimerStateStore.PHASE_RUN && !rec.timer.warnFired
                ) {
                    store.save(rec.timer.copy(warnFired = true))
                    app.getSystemService(Vibrator::class.java)
                        ?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }

            ACTION_ACT_OVERRUN -> {
                // Hyperfocus surfacing: kind, bounded, never escalating past +25.
                val rec = ServiceLocator.timerStateStore.recover(systemBootCount(app))
                if (rec is TimerStateStore.Recovery.Running &&
                    rec.timer.phase == TimerStateStore.PHASE_OVERRUN
                ) {
                    val overMin = ((SystemClock.elapsedRealtime() - rec.timer.endElapsedRealtimeMillis)
                        .coerceAtLeast(0) / 60_000).toInt()
                    Notifications.postSurfacePrompt(app, overMin, rec.timer.label)
                    AlertBridge.showSurface(app, overMin, rec.timer.label)
                    if (overMin < 20) {
                        app.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            rec.timer.endElapsedRealtimeMillis + 25 * 60_000L,
                            TimerService.overrunAlarm(app),
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_REMINDER_ALARM = "com.cztask.action.REMINDER_ALARM"
        const val ACTION_DAILY_RECONCILE = "com.cztask.action.DAILY_RECONCILE"
        const val ACTION_TIMER_FIRE = "com.cztask.action.TIMER_FIRE"
        const val ACTION_ACT_WARN = "com.cztask.action.ACT_WARN"
        const val ACTION_ACT_OVERRUN = "com.cztask.action.ACT_OVERRUN"
    }
}
