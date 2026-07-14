package com.cztask.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.res.ResourcesCompat
import com.cztask.R
import com.cztask.ServiceLocator
import com.cztask.alarm.Notifications
import com.cztask.contract.Ids
import com.cztask.timer.TimerService
import kotlinx.coroutines.launch

/**
 * Full-screen forced choice — the implementation-intention executor. Three
 * skins: checkpoint fired (START / SNOOZE 10 / DONE), TIME UP (+5 / DONE),
 * SURFACE? (+5 / DONE). Turns the screen on and shows over the lock.
 *
 * Durability contract: this window is EPHEMERAL (measured homing can kill
 * it); every choice writes through repositories/services, and the matching
 * notification was posted before this launched. Built programmatically —
 * three text pills on black need no layout inflation.
 */
class AlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val mode = intent.getIntExtra(EXTRA_MODE, MODE_TIME_UP)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val pixel = ResourcesCompat.getFont(this, R.font.press_start_2p)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
        }

        fun title(text: String, color: Int) = root.addView(TextView(this).apply {
            this.text = text
            textSize = 22f
            typeface = pixel
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        })

        fun line(text: String) = root.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFF8F8F8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 18)
        })

        fun pill(text: String, color: Int, onTap: () -> Unit) = root.addView(
            TextView(this).apply {
                this.text = text
                textSize = 16f
                typeface = pixel
                setTextColor(color)
                gravity = Gravity.CENTER
                setPadding(44, 22, 44, 22)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 40f
                    setColor(0xFF203048.toInt())
                }
                setOnClickListener { onTap() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 12; gravity = Gravity.CENTER },
        )

        when (mode) {
            MODE_CHECKPOINT -> {
                val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                val duration = intent.getIntExtra(EXTRA_DURATION_SECONDS, 0)
                    .takeIf { it > 0 } ?: ServiceLocator.pinStore.lastFocusSeconds
                title("CHECKPOINT", 0xFF64B5F6.toInt())
                line(label.uppercase().take(24))
                pill("▶ START ${TimerService.format(duration * 1000L)}", 0xFFF0C018.toInt()) {
                    TimerService.start(this, presetId = -1L, durationSeconds = duration,
                        taskId = taskId, taskLabel = label)
                    dismissCheckpoint(reminderId)
                }
                pill("SNOOZE 10", 0xFFF8F8F8.toInt()) {
                    ServiceLocator.appScope.launch {
                        ServiceLocator.reminderRepository.snooze(reminderId, 10)
                        ServiceLocator.reminderScheduler.reconcile(applicationContext)
                    }
                    dismissCheckpoint(reminderId)
                }
                pill("DONE", 0xFF9E9E9E.toInt()) { dismissCheckpoint(reminderId) }
            }
            MODE_SURFACE -> {
                val over = intent.getIntExtra(EXTRA_OVER_MINUTES, 0)
                title("SURFACE?", 0xFFF0C018.toInt())
                line("+$over MIN OVER" + if (label.isNotEmpty()) "\n${label.uppercase().take(18)}" else "")
                pill("+5 KEEP GOING", 0xFFF0C018.toInt()) {
                    TimerService.extend(this); finish()
                }
                pill("DONE", 0xFFF8F8F8.toInt()) {
                    TimerService.ackDone(this); finish()
                }
            }
            else -> {  // MODE_TIME_UP
                title("TIME UP", 0xFFE23D28.toInt())
                if (label.isNotEmpty()) line(label.uppercase().take(18))
                pill("+5 KEEP GOING", 0xFFF0C018.toInt()) {
                    TimerService.extend(this); finish()
                }
                pill("DONE", 0xFFF8F8F8.toInt()) {
                    TimerService.ackDone(this); finish()
                }
            }
        }

        setContentView(root)
    }

    private fun dismissCheckpoint(reminderId: Long) {
        if (reminderId >= 0) Notifications.cancel(this, Ids.notifForReminder(reminderId))
        finish()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_LABEL = "label"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_DURATION_SECONDS = "duration_seconds"
        const val EXTRA_OVER_MINUTES = "over_minutes"
        const val MODE_TIME_UP = 0
        const val MODE_SURFACE = 1
        const val MODE_CHECKPOINT = 2
    }
}
