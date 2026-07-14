package com.cztask.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cztask.R
import com.cztask.ServiceLocator
import com.cztask.data.db.TimerPreset
import com.cztask.data.time.TimerStateStore
import com.cztask.data.time.systemBootCount
import com.cztask.timer.TimerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TimersActivity : ComponentActivity() {

    private val repo get() = ServiceLocator.timerPresetRepository
    private lateinit var adapter: RowAdapter
    private var presets: List<TimerPreset> = emptyList()
    private var stopHintUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = setUpWearList()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repo.observeAll().collect { list ->
                        presets = list
                        render()
                    }
                }
                // Countdown row ticker: re-render each second only while a
                // timer is actually running.
                launch {
                    while (true) {
                        if (runningTimer() != null) render()
                        delay(1000)
                    }
                }
            }
        }
    }

    private fun runningTimer(): TimerStateStore.RunningTimer? =
        (ServiceLocator.timerStateStore.recover(systemBootCount(this))
            as? TimerStateStore.Recovery.Running)?.timer

    private fun render() {
        adapter.submit(buildList {
            runningTimer()?.let { t ->
                val left = t.endElapsedRealtimeMillis - SystemClock.elapsedRealtime()
                if (left > 0) add(
                    Row.Item(
                        glyph = "■",
                        title = TimerService.format(left) +
                            (t.label?.let { " · ${it.take(10).uppercase()}" } ?: ""),
                        // Timebox protection (A5): a running act must not die to
                        // a sleeve tap. Tap only hints; HOLD stops.
                        subtitle = if (stopHintUntil > SystemClock.elapsedRealtime())
                            getString(R.string.hold_to_stop) else null,
                        onTap = {
                            stopHintUntil = SystemClock.elapsedRealtime() + 1500
                            render()
                        },
                        onLongPress = { TimerService.cancel(this@TimersActivity); render() },
                    )
                )
            }
            add(Row.Center(getString(R.string.add_preset), onTap = ::addPreset))
            for (p in presets) add(
                Row.Item(
                    glyph = "⏱",
                    title = p.label ?: formatDuration(p.durationSeconds),
                    subtitle = if (p.label != null) formatDuration(p.durationSeconds) else null,
                    onTap = {
                        TimerService.start(this@TimersActivity, p.id, p.durationSeconds)
                        render()
                    },
                    onLongPress = {
                        lifecycleScope.launch { repo.delete(p.id) }
                    },
                )
            )
        })
    }

    private fun addPreset() {
        val minutes = intArrayOf(1, 3, 5, 10, 15, 20, 25, 30, 45, 60, 90)
        startActivityForResult(
            PickerActivity.intent(
                this, getString(R.string.pick_minutes_duration), minutes,
                minutes.map { "$it min" }.toTypedArray(),
            ),
            RC_DURATION,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_DURATION && resultCode == RESULT_OK) {
            val min = PickerActivity.result(data) ?: return
            lifecycleScope.launch { repo.add(label = null, durationSeconds = min * 60) }
        }
    }

    private companion object { const val RC_DURATION = 31 }
}
