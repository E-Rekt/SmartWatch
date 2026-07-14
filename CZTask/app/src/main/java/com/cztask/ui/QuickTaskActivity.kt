package com.cztask.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.cztask.R
import com.cztask.ServiceLocator
import com.cztask.data.db.Task
import kotlinx.coroutines.launch

/**
 * Step 5: quick capture. One purpose — get a thought into the task list with
 * the fewest possible interactions. Launches the Wear input flow immediately
 * (voice / emoji / keyboard chooser; the watch remembers the last-used
 * method, so after picking voice once this becomes press -> speak -> done).
 *
 * Exposed as a LAUNCHER activity so Wear 2's hardware-button customization
 * (Settings > Personalization > Customize hardware buttons) can bind a
 * pusher to it, and STEM_1 opens it from inside the app. Tasks land with
 * SOURCE_VOICE so step-6+ features can distinguish capture channel.
 */
class QuickTaskActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchTextInput(RC_CAPTURE, getString(R.string.input_task_label))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_CAPTURE && resultCode == RESULT_OK) {
            textInputResult(data)?.let { title ->
                ServiceLocator.appScope.launch {
                    ServiceLocator.taskRepository.add(title, Task.SOURCE_VOICE)
                }
                Toast.makeText(this, getString(R.string.quick_task_added, title), Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    private companion object { const val RC_CAPTURE = 41 }
}
