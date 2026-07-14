package com.cztask.alarm

import android.content.Context
import android.content.Intent
import com.cztask.ui.AlertActivity

/**
 * Launch full-screen forced-choice alerts from engine code (receiver/service).
 * Direct startActivity is legal on API 28 (background-activity-launch
 * restrictions begin in Q) — the measured platform advantage this design
 * leans on. The matching notification is ALWAYS posted first by the caller:
 * the measured aggressive homing can dismiss any activity, so the durable
 * layer is the notification and the DB, never the alert window.
 */
object AlertBridge {

    fun showTimeUp(context: Context, label: String?) =
        show(context, AlertActivity.MODE_TIME_UP) {
            putExtra(AlertActivity.EXTRA_LABEL, label)
        }

    fun showSurface(context: Context, overMinutes: Int, label: String?) =
        show(context, AlertActivity.MODE_SURFACE) {
            putExtra(AlertActivity.EXTRA_LABEL, label)
            putExtra(AlertActivity.EXTRA_OVER_MINUTES, overMinutes)
        }

    fun showCheckpoint(
        context: Context,
        reminderId: Long,
        label: String,
        taskId: Long,
        durationSeconds: Int,
    ) = show(context, AlertActivity.MODE_CHECKPOINT) {
        putExtra(AlertActivity.EXTRA_REMINDER_ID, reminderId)
        putExtra(AlertActivity.EXTRA_LABEL, label)
        putExtra(AlertActivity.EXTRA_TASK_ID, taskId)
        putExtra(AlertActivity.EXTRA_DURATION_SECONDS, durationSeconds)
    }

    private inline fun show(context: Context, mode: Int, config: Intent.() -> Unit) {
        runCatching {
            context.startActivity(
                Intent(context, AlertActivity::class.java)
                    .putExtra(AlertActivity.EXTRA_MODE, mode)
                    .apply(config)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }
}
