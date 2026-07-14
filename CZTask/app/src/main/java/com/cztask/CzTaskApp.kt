package com.cztask

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.cztask.alarm.Notifications
import kotlinx.coroutines.launch

class CzTaskApp : Application() {

    private val appScope get() = ServiceLocator.appScope

    override fun onCreate() {
        appStartUptimeMillis = SystemClock.uptimeMillis()
        super.onCreate()
        ServiceLocator.init(this)
        Notifications.ensureChannels(this)

        // TIME_SET is NOT delivered to manifest receivers on this firmware
        // (measured — see docs/data-layer-design.md); a runtime receiver covers
        // clock changes while any component of this process lives, and the
        // daily backstop alarm covers the rest.
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                appScope.launch { ServiceLocator.reminderScheduler.reconcile(applicationContext) }
            }
        }, IntentFilter(Intent.ACTION_TIME_CHANGED))

        // App start is a reconcile entry point (design contract).
        appScope.launch { ServiceLocator.reminderScheduler.reconcile(this@CzTaskApp) }
    }

    companion object {
        /** Cold-start anchor for the CZTASK_BENCH log (step-3 stack benchmark). */
        var appStartUptimeMillis: Long = 0L
            private set
    }
}
