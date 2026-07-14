package com.cztask.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.cztask.R
import com.cztask.contract.Ids
import com.cztask.ui.MainActivity

/**
 * Notification plumbing. API 28 requires channels. Kept deliberately small —
 * two channels (reminders, timers), a couple of builders. Wear OS 2 renders
 * these on the watch and in the stream automatically; no Wear-specific extender
 * is needed for basic notifications.
 */
object Notifications {

    private const val CH_REMINDERS = "reminders"
    private const val CH_TIMERS = "timers"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            // Low importance: the running-timer notification is a persistent
            // status, not an alert. The timer-DONE alert reuses the reminders
            // channel so it buzzes.
            NotificationChannel(CH_TIMERS, "Timer status", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun postReminder(context: Context, reminderId: Long, title: String) {
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText("Reminder")
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_VIBRATE)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(Ids.notifForReminder(reminderId), n)
    }

    /** Persistent status notification a foreground timer service attaches to. */
    fun timerRunning(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CH_TIMERS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Timer running")
            .setContentText(text)
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun postTimerDone(context: Context) {
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Timer finished")
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_VIBRATE or Notification.DEFAULT_SOUND)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(Ids.NOTIF_TIMER_DONE, n)
    }

    /** Hyperfocus surfacing: durable layer behind the SURFACE alert. Kind,
     *  factual, dismissable — never an alarm bell. */
    fun postSurfacePrompt(context: Context, overMinutes: Int, label: String?) {
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Surface?")
            .setContentText("+$overMinutes min over" + (label?.let { " · ${it.take(14)}" } ?: ""))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(0, 120, 100, 120))   // double-buzz signature
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(Ids.NOTIF_ACT_STATUS, n)
    }

    /** Reported once per lost timer (store self-clears): reboot wiped the
     *  elapsed-axis anchor, so the countdown could not survive. */
    fun postTimerLost(context: Context) {
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Timer canceled by restart")
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(Ids.NOTIF_TIMER_DONE, n)
    }

    fun cancel(context: Context, id: Int) =
        context.getSystemService(NotificationManager::class.java).cancel(id)
}
