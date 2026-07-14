package com.cztask.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cztask.R
import com.cztask.ServiceLocator
import com.cztask.alarm.Notifications
import com.cztask.contract.Ids
import kotlinx.coroutines.launch

class TasksActivity : ComponentActivity() {

    private val repo get() = ServiceLocator.taskRepository
    private lateinit var adapter: RowAdapter

    // Long-press arm state: exactly one row shows ★ PIN | DELETE?, 3 s revert.
    private var armedTaskId: Long = -1L
    private var cachedTasks: List<com.cztask.data.db.Task> = emptyList()
    private val armReverter = android.os.Handler(android.os.Looper.getMainLooper())
    private val revertArm = Runnable { armedTaskId = -1L; render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = setUpWearList()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeAll().collect { tasks ->
                    cachedTasks = tasks
                    render()
                }
            }
        }
    }

    private fun arm(taskId: Long) {
        armedTaskId = taskId
        armReverter.removeCallbacks(revertArm)
        armReverter.postDelayed(revertArm, 3000)
        render()
    }

    private fun disarm() {
        armReverter.removeCallbacks(revertArm)
        armedTaskId = -1L
    }

    private fun render() {
        val tasks = cachedTasks
        adapter.submit(buildList {
            add(Row.Center(getString(R.string.add_task), onTap = ::addTask))
            if (tasks.isEmpty()) add(Row.Center(getString(R.string.empty_tasks), dim = true))
            for (t in tasks) {
                if (t.id == armedTaskId) {
                    add(Row.Armed(
                        onPin = {
                            disarm()
                            ServiceLocator.appScope.launch { repo.pin(t.id) }
                            render()
                        },
                        onDelete = {
                            disarm()
                            ServiceLocator.appScope.launch {
                                val orphaned = repo.delete(t.id)
                                cancelAndReconcile("delete", orphaned, cancelNotifs = true)
                            }
                        },
                    ))
                } else {
                    add(Row.Item(
                        glyph = if (t.done) "✓" else "○",
                        title = t.title,
                        dimTitle = t.done,
                        onTap = {
                            // appScope: the re-arm must survive swipe-dismiss.
                            ServiceLocator.appScope.launch {
                                val affected = repo.setDone(t.id, !t.done)
                                cancelAndReconcile("setDone", affected, cancelNotifs = t.done.not())
                            }
                        },
                        onLongPress = { arm(t.id) },
                    ))
                }
            }
        })
    }

    override fun onDestroy() {
        armReverter.removeCallbacks(revertArm)
        super.onDestroy()
    }

    private fun addTask() = launchTextInput(RC_ADD_TASK, getString(R.string.input_task_label))

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_ADD_TASK && resultCode == RESULT_OK) {
            val title = textInputResult(data) ?: return
            lifecycleScope.launch { repo.add(title) }
        }
    }

    /** The step-4 contract in action: affected reminder ids dismiss any showing
     *  notifications, and the alarm plan is re-derived. */
    private suspend fun cancelAndReconcile(op: String, ids: List<Long>, cancelNotifs: Boolean) {
        val app = applicationContext   // runs in appScope; never hold the activity
        if (ids.isNotEmpty()) {
            Log.i("CZTASK_WORKLIST", "$op -> reminders $ids (cancelNotifs=$cancelNotifs)")
            if (cancelNotifs) for (id in ids) Notifications.cancel(app, Ids.notifForReminder(id))
        }
        ServiceLocator.reminderScheduler.reconcile(app)
    }

    private companion object { const val RC_ADD_TASK = 11 }
}
