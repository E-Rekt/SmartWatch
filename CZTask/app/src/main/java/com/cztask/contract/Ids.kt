package com.cztask.contract

/**
 * Cross-step allocation of notification ids and PendingIntent request codes.
 * Pinned in step 2 because collisions here are silent (a reminder notification
 * replacing the running-timer notification) and every later step reads this.
 *
 * Notification ids and request codes are separate namespaces in the OS, but we
 * use one disjoint numbering for both to keep reasoning simple:
 *   1..99        fixed singletons
 *   1000..∞      reminders, 1000 + (reminder.id % 1_000_000)
 */
object Ids {
    const val NOTIF_TIMER_RUNNING = 1
    const val NOTIF_TIMER_DONE = 2
    const val NOTIF_MISSED_SUMMARY = 3
    const val NOTIF_CLOCK_WARNING = 4

    const val RC_ALARM_NEXT_FIRE = 10     // the single coalesced RTC_WAKEUP alarm
    const val RC_TIMER_ELAPSED_ALARM = 11
    const val RC_DAILY_RECONCILE = 12     // elapsed-axis daily backstop

    fun notifForReminder(reminderId: Long): Int = (1000 + (reminderId % 1_000_000)).toInt()
}
