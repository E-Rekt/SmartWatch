package com.cztask.data.time

import android.content.Context

/**
 * The ONE Thing: today's single pinned critical task, plus small focus prefs.
 * SharedPreferences (same rationale as TimerStateStore): readable anywhere
 * without Room-open cost, survives the measured 30-60 s process kills,
 * commit() because async writes are the wrong tool under SIGKILL.
 *
 * The pin auto-expires: it is only valid for the epoch-day it was set.
 * Task-existence/done validation happens in TaskRepository.featured() —
 * this store knows ids, not rows.
 */
class PinStore(context: Context) {
    private val sp = context.getSharedPreferences("pin_store", Context.MODE_PRIVATE)

    fun pin(taskId: Long, epochDay: Long) {
        sp.edit().putLong(K_ID, taskId).putLong(K_DAY, epochDay).commit()
    }

    fun clear() {
        sp.edit().remove(K_ID).remove(K_DAY).commit()
    }

    /** Pinned task id if the pin is for today; stale pins self-clear. */
    fun pinnedId(todayEpochDay: Long): Long? {
        val id = sp.getLong(K_ID, -1L)
        if (id < 0) return null
        if (sp.getLong(K_DAY, -1L) != todayEpochDay) {
            clear()
            return null
        }
        return id
    }

    /** Last-used focus act duration; GO uses it so starting is zero decisions. */
    var lastFocusSeconds: Int
        get() = sp.getInt(K_FOCUS_SECONDS, 1500)
        set(value) {
            sp.edit().putInt(K_FOCUS_SECONDS, value).commit()
        }

    private companion object {
        const val K_ID = "pinned_task_id"
        const val K_DAY = "pinned_epoch_day"
        const val K_FOCUS_SECONDS = "last_focus_seconds"
    }
}
