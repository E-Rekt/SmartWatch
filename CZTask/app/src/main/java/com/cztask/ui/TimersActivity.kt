package com.cztask.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cztask.R
import com.cztask.ServiceLocator
import kotlinx.coroutines.launch

class TimersActivity : ComponentActivity() {

    private val repo get() = ServiceLocator.timerPresetRepository
    private lateinit var adapter: RowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = setUpWearList()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeAll().collect { presets ->
                    adapter.submit(buildList {
                        add(Row.Center(getString(R.string.add_preset), onTap = ::addPreset))
                        for (p in presets) add(
                            Row.Item(
                                glyph = "⏱",
                                title = p.label ?: formatDuration(p.durationSeconds),
                                subtitle = if (p.label != null) formatDuration(p.durationSeconds) else null,
                                onTap = {
                                    Toast.makeText(this@TimersActivity, R.string.timers_step4, Toast.LENGTH_SHORT).show()
                                },
                                onLongPress = {
                                    lifecycleScope.launch { repo.delete(p.id) }
                                },
                            )
                        )
                    })
                }
            }
        }
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
