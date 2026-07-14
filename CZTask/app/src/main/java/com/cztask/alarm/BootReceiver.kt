package com.cztask.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cztask.ServiceLocator
import com.cztask.data.time.TimerStateStore
import com.cztask.data.time.systemBootCount
import com.cztask.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BOOT_COMPLETED and TIMEZONE_CHANGED — both measured as delivered to manifest
 * receivers on this firmware. On boot: re-derive and re-arm all alarms (they
 * were wiped), surface a lost running timer once, and launch the app (the
 * user wants CZTask as the watch's primary experience; API 28 predates
 * background-activity-start restrictions, so this works from a receiver).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val isBoot = intent.action == Intent.ACTION_BOOT_COMPLETED
        Log.i("CZTASK_ALARM", "BootReceiver: ${intent.action}")

        if (isBoot) {
            when (val rec = ServiceLocator.timerStateStore.recover(systemBootCount(app))) {
                is TimerStateStore.Recovery.LostToReboot -> Notifications.postTimerLost(app)
                is TimerStateStore.Recovery.Running -> {
                    // elapsed anchor survived?? bootCount matched — resume the
                    // countdown notification path via the backup alarm check.
                    Log.i("CZTASK_ALARM", "timer still valid after ${intent.action}: $rec")
                }
                TimerStateStore.Recovery.None -> Unit
            }
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ServiceLocator.reminderScheduler.reconcile(app)
            } finally {
                pending.finish()
            }
        }

        if (isBoot) {
            app.startActivity(
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
