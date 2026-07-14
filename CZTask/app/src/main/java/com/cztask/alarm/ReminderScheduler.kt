package com.cztask.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.cztask.contract.Ids
import com.cztask.data.db.TaskDao
import com.cztask.data.repo.ReminderRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The whole alarm layer is one idempotent operation: reconcile().
 * Fire whatever schedulePlan says is due, then arm exactly one coalesced
 * RTC_WAKEUP alarm at the next occurrence. Called from every entry point:
 * app start, every reminder/task mutation, alarm fire, boot, timezone change,
 * TIME_SET (runtime receiver), and the daily backstop.
 *
 * Measured on this firmware (docs/data-layer-design.md): BOOT_COMPLETED and
 * TIMEZONE_CHANGED reach manifest receivers; TIME_SET does NOT — hence the
 * runtime receiver in CzTaskApp plus the daily elapsed-axis backstop alarm.
 */
class ReminderScheduler(
    private val reminders: ReminderRepository,
    private val tasks: TaskDao,
) {
    // Entry points race by design (alarm into a dead process = app-start
    // reconcile + receiver reconcile ~30 ms apart, measured): without mutual
    // exclusion both can read the same dueNow row before either markFired
    // commits and the user gets a double alert. Serializing makes the second
    // reconcile see the fired state and no-op.
    private val lock = Mutex()

    suspend fun reconcile(context: Context) = lock.withLock {
        var plan = reminders.schedulePlan()
        val firedCount = plan.dueNow.size

        for (due in plan.dueNow) {
            val label = due.reminder.taskId?.let { tasks.title(it) }
                ?: due.reminder.label.orEmpty().ifEmpty { "Reminder" }
            Notifications.postReminder(context, due.reminder.id, label)
            // Stamp the SCHEDULED occurrence instant (not delivery time) —
            // the backward-clock-jump dedup depends on it.
            reminders.markFired(due.reminder.id, due.occurrenceUtcMillis)
        }
        // Firing mutates the rows the plan was derived from; re-derive before arming.
        if (plan.dueNow.isNotEmpty()) plan = reminders.schedulePlan()

        val am = context.getSystemService(AlarmManager::class.java)
        val pi = reminderAlarmIntent(context)
        am.cancel(pi)
        plan.nextFireAtUtcMillis?.let { at ->
            // Exact-and-idle is permission-free on targetSdk 28. RTC targets are
            // wall-clock-relative, so a corrected clock self-corrects the fire.
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        armDailyBackstop(context)
        Log.i(TAG, "reconcile: fired=$firedCount next=${plan.nextFireAtUtcMillis} ids=${plan.reminderIdsAtNextFire}")
    }

    /** Inexact daily tick on the ELAPSED axis — immune to wall-clock nonsense.
     *  Catches anything a missed broadcast or edge case stranded. Re-armed on
     *  every reconcile and on boot; FLAG_UPDATE_CURRENT makes that idempotent. */
    private fun armDailyBackstop(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,   // must fire while asleep — that's when stranding happens
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_DAY,
            AlarmManager.INTERVAL_DAY,
            broadcast(context, Ids.RC_DAILY_RECONCILE, AlarmReceiver.ACTION_DAILY_RECONCILE),
        )
    }

    private fun reminderAlarmIntent(context: Context): PendingIntent =
        broadcast(context, Ids.RC_ALARM_NEXT_FIRE, AlarmReceiver.ACTION_REMINDER_ALARM)

    companion object {
        private const val TAG = "CZTASK_ALARM"

        fun broadcast(context: Context, requestCode: Int, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, AlarmReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
