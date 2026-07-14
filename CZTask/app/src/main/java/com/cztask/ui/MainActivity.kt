package com.cztask.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cztask.CzTaskApp
import com.cztask.R
import com.cztask.ServiceLocator
import com.cztask.data.time.ClockStatus
import com.cztask.data.time.SystemTimeSource
import com.cztask.data.time.TimerStateStore
import com.cztask.data.time.systemBootCount
import com.cztask.timer.TimerService
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * NOW home (Phase A): one hero card answering "what should I be doing right
 * now?" — running act, or the featured task with one-tap GO, or all clear —
 * with the section menu below the fold.
 *
 * Stems: STEM_1 = quick capture (sacred). STEM_2 tap = Timers; STEM_2 hold =
 * instant Focus Act on the featured task (the blind "just start" gesture).
 */
class MainActivity : ComponentActivity() {

    private lateinit var adapter: RowAdapter
    private var benchLogged = false

    private var heroState: NowCardView.State = NowCardView.State.AllClear(null)
    private var openCount = 0
    private var nextCheckpoint: String? = null
    private var featuredTitle: String? = null
    private var featuredId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = setUpWearList(centering = false)

        window.decorView.post {
            if (!benchLogged) {
                benchLogged = true
                val cold = SystemClock.uptimeMillis() - CzTaskApp.appStartUptimeMillis
                val rt = Runtime.getRuntime()
                val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                Log.i("CZTASK_BENCH", "coldStartMs=$cold heapUsedMb=$usedMb heapMaxMb=${rt.maxMemory() / (1024 * 1024)}")
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Refresh loop: cheap queries over tens of rows, foreground only.
                while (true) {
                    refreshState()
                    render()
                    delay(1000)
                }
            }
        }
    }

    private suspend fun refreshState() {
        val timer = (ServiceLocator.timerStateStore.recover(systemBootCount(this))
            as? TimerStateStore.Recovery.Running)?.timer

        val plan = ServiceLocator.reminderRepository.schedulePlan()
        nextCheckpoint = plan.nextFireAtUtcMillis?.let { at ->
            val zdt = Instant.ofEpochMilli(at).atZone(SystemTimeSource.zone())
            "next %02d:%02d".format(zdt.hour, zdt.minute)
        }
        openCount = ServiceLocator.db.taskDao().openCount()

        heroState = if (timer != null && timer.endElapsedRealtimeMillis > SystemClock.elapsedRealtime()) {
            featuredTitle = null; featuredId = -1L
            NowCardView.State.Act(timer.endElapsedRealtimeMillis, timer.label)
        } else {
            val (task, pinned) = ServiceLocator.taskRepository.featured()
            if (task != null) {
                featuredTitle = task.title; featuredId = task.id
                NowCardView.State.Featured(
                    task.id, task.title, pinned, ServiceLocator.pinStore.lastFocusSeconds,
                )
            } else {
                featuredTitle = null; featuredId = -1L
                NowCardView.State.AllClear(nextCheckpoint)
            }
        }
    }

    private fun render() {
        adapter.submit(buildList {
            if (ServiceLocator.lastClockStatus != ClockStatus.OK) {
                add(Row.Center(getString(R.string.clock_warning)))
            }
            add(Row.Hero { hero ->
                hero.state = heroState
                hero.onGo = { taskId, title -> startAct(taskId, title) }
                hero.onOpenTimers = { open(TimersActivity::class.java) }
                hero.onNotNow = { taskId, wasPinned ->
                    ServiceLocator.appScope.launch {
                        ServiceLocator.taskRepository.notNow(taskId, wasPinned)
                    }
                }
            })
            add(Row.Center(getString(R.string.menu_tasks), onTap = { open(TasksActivity::class.java) }))
            add(Row.Center(taskSubtitle(), dim = true))
            add(Row.Center(getString(R.string.menu_reminders), onTap = { open(RemindersActivity::class.java) }))
            add(Row.Center(nextCheckpoint ?: "—", dim = true))
            add(Row.Center(getString(R.string.menu_timers), onTap = { open(TimersActivity::class.java) }))
        })
    }

    private fun taskSubtitle() = when (openCount) {
        0 -> "all clear"
        1 -> "1 open"
        else -> "$openCount open"
    }

    private fun startAct(taskId: Long, title: String) {
        TimerService.start(
            this,
            presetId = -1L,
            durationSeconds = ServiceLocator.pinStore.lastFocusSeconds,
            taskId = taskId,
            taskLabel = title,
        )
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    // Measured stem mapping (CZProbe): top pusher = STEM_1, bottom = STEM_2.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_STEM_1 -> { open(QuickTaskActivity::class.java); true }
        KeyEvent.KEYCODE_STEM_2 -> { event.startTracking(); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_STEM_2) {
            // The blind gesture: act running -> +5 KEEP GOING; otherwise
            // instant act on the featured task.
            val id = featuredId
            val title = featuredTitle
            when {
                heroState is NowCardView.State.Act -> TimerService.extend(this)
                id >= 0 && title != null && heroState is NowCardView.State.Featured ->
                    startAct(id, title)
                else -> open(TimersActivity::class.java)
            }
            true
        } else super.onKeyLongPress(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_STEM_2) {
            if (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS == 0) {
                open(TimersActivity::class.java)
            }
            true
        } else super.onKeyUp(keyCode, event)
}
