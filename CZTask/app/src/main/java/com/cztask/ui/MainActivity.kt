package com.cztask.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import com.cztask.CzTaskApp
import com.cztask.R
import com.cztask.ServiceLocator
import com.cztask.data.time.ClockStatus

class MainActivity : ComponentActivity() {

    private lateinit var adapter: RowAdapter
    private var benchLogged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = setUpWearList()

        // The stack-decision benchmark the brief demands: cold start to first
        // frame, and heap, logged once.  adb logcat -s CZTASK_BENCH
        window.decorView.post {
            if (!benchLogged) {
                benchLogged = true
                val cold = SystemClock.uptimeMillis() - CzTaskApp.appStartUptimeMillis
                val rt = Runtime.getRuntime()
                val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                Log.i("CZTASK_BENCH", "coldStartMs=$cold heapUsedMb=$usedMb heapMaxMb=${rt.maxMemory() / (1024 * 1024)}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.submit(buildMenu())
    }

    private fun buildMenu(): List<Row> = buildList {
        if (ServiceLocator.lastClockStatus != ClockStatus.OK) {
            add(Row.Center(getString(R.string.clock_warning)))
        }
        add(Row.Center(getString(R.string.menu_tasks), onTap = { open(TasksActivity::class.java) }))
        add(Row.Center(getString(R.string.menu_reminders), onTap = { open(RemindersActivity::class.java) }))
        add(Row.Center(getString(R.string.menu_timers), onTap = { open(TimersActivity::class.java) }))
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    // Measured stem mapping (CZProbe): top pusher = STEM_1, bottom = STEM_2.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_STEM_1 -> { open(TasksActivity::class.java); true }
        KeyEvent.KEYCODE_STEM_2 -> { open(TimersActivity::class.java); true }
        else -> super.onKeyDown(keyCode, event)
    }
}
