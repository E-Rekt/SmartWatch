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
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch

class RemindersActivity : ComponentActivity() {

    private val repo get() = ServiceLocator.reminderRepository
    private lateinit var adapter: RowAdapter

    // Four-step add flow (label -> repeat -> hour -> minute); pending state is
    // fine to lose on process death mid-flow.
    private var pendingLabel: String? = null
    private var pendingRepeat: Int = REPEAT_DAILY
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
                                        // appScope, not lifecycleScope: swipe-dismiss
                                        // must not cancel the re-arm after the write.
                                        ServiceLocator.appScope.launch {
                                            repo.setEnabled(r.id, !r.enabled)
                                            reconcile()
                                        }
                                    },
                                    onLongPress = {
                                        ServiceLocator.appScope.launch {
                                            repo.delete(r.id)
                                            reconcile()
                                        }
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
                    PickerActivity.intent(
                        this, getString(R.string.pick_repeat), intArrayOf(0, 1, 2, 3, 4),
                        arrayOf("Daily", "Weekdays", "Weekends", "Today", "Tomorrow"),
                    ),
                    RC_REPEAT,
                )
            }
            RC_REPEAT -> {
                pendingRepeat = PickerActivity.result(data) ?: return
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
                val repeat = pendingRepeat
                val at = LocalTime.of(hour, minute)
                ServiceLocator.appScope.launch {
                    when (repeat) {
                        REPEAT_DAILY -> repo.addRepeating(null, label, DAILY_MASK, at)
                        REPEAT_WEEKDAYS -> repo.addRepeating(null, label, WEEKDAY_MASK, at)
                        REPEAT_WEEKENDS -> repo.addRepeating(null, label, WEEKEND_MASK, at)
                        REPEAT_TODAY -> repo.addOneShot(null, label, LocalDate.now(), at)
                        REPEAT_TOMORROW -> repo.addOneShot(null, label, LocalDate.now().plusDays(1), at)
                    }
                    reconcile()
                }
            }
        }
    }

    /** OS alarms are external state — every reminder mutation re-derives them. */
    private suspend fun reconcile() =
        ServiceLocator.reminderScheduler.reconcile(applicationContext)

    private companion object {
        const val RC_LABEL = 21
        const val RC_HOUR = 22
        const val RC_MINUTE = 23
        const val RC_REPEAT = 24
        const val DAILY_MASK = 0b1111111
        const val WEEKDAY_MASK = 0b0011111   // bit0=Mon … bit6=Sun
        const val WEEKEND_MASK = 0b1100000
        const val REPEAT_DAILY = 0
        const val REPEAT_WEEKDAYS = 1
        const val REPEAT_WEEKENDS = 2
        const val REPEAT_TODAY = 3
        const val REPEAT_TOMORROW = 4
    }
}
