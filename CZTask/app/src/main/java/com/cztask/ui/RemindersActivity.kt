package com.cztask.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cztask.R
import com.cztask.ServiceLocator
import com.cztask.data.time.SystemTimeSource
import java.time.LocalTime
import kotlinx.coroutines.launch

class RemindersActivity : ComponentActivity() {

    private val repo get() = ServiceLocator.reminderRepository
    private lateinit var adapter: RowAdapter

    // Three-step add flow (label -> hour -> minute); pending state is fine to
    // lose on process death mid-flow.
    private var pendingLabel: String? = null
    private var pendingHour: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = setUpWearList()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeAll().collect { rows ->
                    adapter.submit(buildList {
                        add(Row.Center(getString(R.string.add_reminder), onTap = ::startAdd))
                        if (rows.isEmpty()) add(Row.Center(getString(R.string.empty_reminders), dim = true))
                        for (rwt in rows) {
                            val r = rwt.reminder
                            add(
                                Row.Item(
                                    glyph = if (r.enabled) "●" else "◌",
                                    title = rwt.displayLabel,
                                    subtitle = "${formatRule(r)} · ${formatNext(r, SystemTimeSource)}",
                                    dimTitle = !r.enabled,
                                    onTap = {
                                        lifecycleScope.launch { repo.setEnabled(r.id, !r.enabled) }
                                    },
                                    onLongPress = {
                                        lifecycleScope.launch { repo.delete(r.id) }
                                    },
                                )
                            )
                        }
                    })
                }
            }
        }
    }

    private fun startAdd() = launchTextInput(RC_LABEL, getString(R.string.input_reminder_label))

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            RC_LABEL -> {
                pendingLabel = textInputResult(data) ?: return
                startActivityForResult(
                    PickerActivity.intent(this, getString(R.string.pick_hour), IntArray(24) { it }),
                    RC_HOUR,
                )
            }
            RC_HOUR -> {
                pendingHour = PickerActivity.result(data) ?: return
                val minutes = IntArray(12) { it * 5 }
                startActivityForResult(
                    PickerActivity.intent(
                        this, getString(R.string.pick_minute), minutes,
                        Array(12) { "%02d:%02d".format(pendingHour, it * 5) },
                    ),
                    RC_MINUTE,
                )
            }
            RC_MINUTE -> {
                val minute = PickerActivity.result(data) ?: return
                val label = pendingLabel ?: return
                val hour = pendingHour
                if (hour !in 0..23) return
                lifecycleScope.launch {
                    repo.addRepeating(null, label, DAILY_MASK, LocalTime.of(hour, minute))
                }
            }
        }
    }

    private companion object {
        const val RC_LABEL = 21
        const val RC_HOUR = 22
        const val RC_MINUTE = 23
        const val DAILY_MASK = 0b1111111
    }
}
