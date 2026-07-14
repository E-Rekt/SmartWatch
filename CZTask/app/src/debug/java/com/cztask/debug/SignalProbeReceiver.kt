package com.cztask.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Logs delivery to logcat AND SharedPreferences (survives logcat clears and
 *  proves the process was spawned for the broadcast). Read back with:
 *  adb shell "run-as com.cztask cat shared_prefs/signal_probe.xml" */
class SignalProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val stamp = "${intent.action}@${System.currentTimeMillis()}"
        Log.i("CZTASK_SIGNAL", stamp)
        context.getSharedPreferences("signal_probe", Context.MODE_PRIVATE)
            .edit().putString(intent.action, stamp).apply()
    }
}
