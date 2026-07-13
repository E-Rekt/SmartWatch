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
import kotlinx.coroutines.launch

class TasksActivity : ComponentActivity() {

    private val repo get() = ServiceLocator.taskRepository
    private lateinit var adapter: RowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = setUpWearList()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeAll().collect { tasks ->
                    adapter.submit(buildList {
                        add(Row.Center(getString(R.string.add_task), onTap = ::addTask))
                        if (tasks.isEmpty()) add(Row.Center(getString(R.string.empty_tasks), dim = true))
                        for (t in tasks) add(
                            Row.Item(
                                glyph = if (t.done) "✓" else "○",
                                title = t.title,
                                dimTitle = t.done,
                                onTap = {
                                    lifecycleScope.launch {
                                        val affected = repo.setDone(t.id, !t.done)
                                        logWorklist("setDone", affected)
                                    }
                                },
                                onLongPress = {
                                    lifecycleScope.launch {
                                        val orphaned = repo.delete(t.id)
                                        logWorklist("delete", orphaned)
                                    }
                                },
                            )
                        )
                    })
                }
            }
        }
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

    /** Step 4 consumes these ids to cancel alarms/notifications; until then we
     *  log them so the contract is visible in the deploy loop. */
    private fun logWorklist(op: String, ids: List<Long>) {
        if (ids.isNotEmpty()) Log.i("CZTASK_WORKLIST", "$op -> cancel reminders $ids")
    }

    private companion object { const val RC_ADD_TASK = 11 }
}
