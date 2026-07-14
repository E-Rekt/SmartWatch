package com.cztask.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cztask.ServiceLocator
import com.cztask.data.time.TimerStateStore
import com.cztask.data.time.systemBootCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Terminus for our own PendingIntents: the coalesced reminder alarm, the
 *  daily reconcile backstop, and the timer's backup completion alarm. */
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
                // Backup path: fires even if TimerService was killed. If the
                // service already handled completion the store is clear and
                // this is a no-op.
                val store = ServiceLocator.timerStateStore
                val rec = store.recover(systemBootCount(app))
                if (rec is TimerStateStore.Recovery.Running) {
                    store.clear()
                    Notifications.postTimerDone(app)
                }
            }
        }
    }

    companion object {
        const val ACTION_REMINDER_ALARM = "com.cztask.action.REMINDER_ALARM"
        const val ACTION_DAILY_RECONCILE = "com.cztask.action.DAILY_RECONCILE"
        const val ACTION_TIMER_FIRE = "com.cztask.action.TIMER_FIRE"
    }
}
