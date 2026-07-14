package com.cztask.data.time

import android.content.Context

/**
 * Running-timer state contract for step 4. Deliberately SharedPreferences, not
 * Room: readable in a receiver without paying database-open cost, and the probe
 * measured 30-60 s foreground kills — a foreground service's memory is not a
 * safe home for the only copy of a running timer.
 *
 * Domain rules:
 *  - The timer lives on the ELAPSED axis (endElapsedRealtimeMillis), immune to
 *    wall-clock changes by construction.
 *  - bootCount anchors the elapsed axis: a mismatch means the device rebooted,
 *    the elapsed anchor is meaningless, and the policy is "a reboot cancels a
 *    running timer" — clear() and (step 4) show a "timer lost to restart" note.
 */
class TimerStateStore(context: Context) {
    private val sp = context.getSharedPreferences("timer_state", Context.MODE_PRIVATE)

    data class RunningTimer(
        val presetId: Long,          // -1 = ad-hoc duration
        val endElapsedRealtimeMillis: Long,
        val bootCount: Long,
        val startedWallUtcMillis: Long,  // display/debug only, never arithmetic
        // Focus Act binding (Phase A): which task this timebox serves.
        // Backward-compatible defaults — old records read as unbound timers.
        val taskId: Long = -1L,
        val label: String? = null,
        val plannedSeconds: Int = 0,
        // Phase B: act lifecycle. phase 0 = RUN, 1 = OVERRUN (past zero,
        // surfacing prompts active). Extensions increment; session row id
        // links the durable focus_session record.
        val phase: Int = PHASE_RUN,
        val warnFired: Boolean = false,
        val extendedCount: Int = 0,
        val sessionId: Long = -1L,
    )

    /** Recovery outcome. LostToReboot is returned exactly once (the record
     *  self-clears) so step 4 can show a "timer lost to restart" note. */
    sealed interface Recovery {
        object None : Recovery
        data class Running(val timer: RunningTimer) : Recovery
        data class LostToReboot(val timer: RunningTimer) : Recovery
    }

    // commit(), not apply(): this store exists because the process dies by
    // SIGKILL; an async-queued write is the wrong tool for its one job.
    fun save(t: RunningTimer) {
        sp.edit()
            .putLong(K_PRESET, t.presetId)
            .putLong(K_END_ELAPSED, t.endElapsedRealtimeMillis)
            .putLong(K_BOOT, t.bootCount)
            .putLong(K_STARTED_WALL, t.startedWallUtcMillis)
            .putLong(K_TASK_ID, t.taskId)
            .putString(K_LABEL, t.label)
            .putInt(K_PLANNED_SECONDS, t.plannedSeconds)
            .putInt(K_PHASE, t.phase)
            .putBoolean(K_WARN_FIRED, t.warnFired)
            .putInt(K_EXTENDED_COUNT, t.extendedCount)
            .putLong(K_SESSION_ID, t.sessionId)
            .commit()
    }

    fun clear() { sp.edit().clear().commit() }

    fun recover(currentBootCount: Long): Recovery {
        val end = sp.getLong(K_END_ELAPSED, Long.MIN_VALUE)
        if (end == Long.MIN_VALUE) return Recovery.None
        val t = RunningTimer(
            presetId = sp.getLong(K_PRESET, -1L),
            endElapsedRealtimeMillis = end,
            bootCount = sp.getLong(K_BOOT, -1L),
            startedWallUtcMillis = sp.getLong(K_STARTED_WALL, 0L),
            taskId = sp.getLong(K_TASK_ID, -1L),
            label = sp.getString(K_LABEL, null),
            plannedSeconds = sp.getInt(K_PLANNED_SECONDS, 0),
            phase = sp.getInt(K_PHASE, PHASE_RUN),
            warnFired = sp.getBoolean(K_WARN_FIRED, false),
            extendedCount = sp.getInt(K_EXTENDED_COUNT, 0),
            sessionId = sp.getLong(K_SESSION_ID, -1L),
        )
        if (t.bootCount != currentBootCount) {
            clear()   // reported once: next call returns None
            return Recovery.LostToReboot(t)
        }
        return Recovery.Running(t)
    }

    companion object {
        const val PHASE_RUN = 0
        const val PHASE_OVERRUN = 1

        private const val K_PRESET = "preset_id"
        private const val K_END_ELAPSED = "end_elapsed_millis"
        private const val K_BOOT = "boot_count"
        private const val K_STARTED_WALL = "started_wall_utc"
        private const val K_TASK_ID = "task_id"
        private const val K_LABEL = "label"
        private const val K_PLANNED_SECONDS = "planned_seconds"
        private const val K_PHASE = "phase"
        private const val K_WARN_FIRED = "warn_fired"
        private const val K_EXTENDED_COUNT = "extended_count"
        private const val K_SESSION_ID = "session_id"
    }
}
