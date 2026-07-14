package com.cztask.data.time

import android.content.Context
import android.provider.Settings

enum class ClockStatus {
    OK,

    /** Wall clock moved against the elapsed-realtime axis since last check
     *  (same boot). Distinguishes "clock was corrected" from "device dozed
     *  for hours" — doze advances both axes together. */
    JUMPED,

    /** Now is behind the highest wall-clock time this app has ever seen. */
    BEHIND_HIGH_WATER,

    /** Now is before the APK was even built — the clock is provably wrong.
     *  The measured field incident (15 months slow) lands here. */
    BEFORE_BUILD,
}

/** Store abstraction keeps ClockGuard JVM-testable; prod impl wraps SharedPreferences. */
interface LongStore {
    fun get(key: String, def: Long): Long
    fun put(key: String, value: Long)
}

/**
 * Detection-only clock sanity. The app cannot SET the system clock (SET_TIME is
 * signature|privileged); the ceiling is detecting nonsense and telling the user
 * (step 3 shows a "check watch time" banner on non-OK). Step 4 still arms
 * alarms regardless — RTC targets are wall-clock-relative and self-correct
 * when the clock does.
 */
class ClockGuard(
    private val store: LongStore,
    private val time: TimeSource,
    private val buildFloorUtcMillis: Long,   // BuildConfig.BUILD_FLOOR_UTC_MILLIS
    private val bootCount: () -> Long,       // Settings.Global.BOOT_COUNT
) {
    /** Call at every process entry point (app start; step 4: every receiver). */
    fun checkAndAdvance(): ClockStatus {
        val now = time.nowUtcMillis()
        val elapsed = time.elapsedRealtimeMillis()
        val boot = bootCount()

        val highWater = store.get(KEY_HIGH_WATER, 0L)
        val lastWall = store.get(KEY_LAST_WALL, 0L)
        val lastElapsed = store.get(KEY_LAST_ELAPSED, 0L)
        val lastBoot = store.get(KEY_LAST_BOOT, -1L)

        // Drift = how far the wall clock moved beyond what real elapsed time
        // explains. Valid only within one boot (elapsedRealtime resets).
        val axisValid = lastBoot == boot && lastWall > 0
        val drift = if (axisValid) (now - lastWall) - (elapsed - lastElapsed) else 0L
        val jumpedForward = drift > JUMP_THRESHOLD_MS
        val jumpedBackward = drift < -JUMP_THRESHOLD_MS

        val status = when {
            now < buildFloorUtcMillis -> ClockStatus.BEFORE_BUILD
            // Axis-corroborated backward step = trustworthy evidence the OLD
            // wall times were fast. Lower the poisoned high-water mark so this
            // reports once instead of BEHIND_HIGH_WATER forever (a sticky
            // false banner would train the user to ignore the guard).
            jumpedBackward -> {
                store.put(KEY_HIGH_WATER, now)
                ClockStatus.JUMPED
            }
            now + TOLERANCE_MS < highWater -> ClockStatus.BEHIND_HIGH_WATER
            jumpedForward -> ClockStatus.JUMPED
            else -> ClockStatus.OK
        }

        // Don't let a suspect forward jump poison the mark; if the new time is
        // real, the next consistent check ratifies and advances it.
        if (now > highWater && !jumpedForward) store.put(KEY_HIGH_WATER, now)
        store.put(KEY_LAST_WALL, now)
        store.put(KEY_LAST_ELAPSED, elapsed)
        store.put(KEY_LAST_BOOT, boot)
        return status
    }

    companion object {
        const val KEY_HIGH_WATER = "clock_high_water_utc"
        const val KEY_LAST_WALL = "clock_last_wall_utc"
        const val KEY_LAST_ELAPSED = "clock_last_elapsed"
        const val KEY_LAST_BOOT = "clock_last_boot_count"
        // Both thresholds are 5 min so a small NTP step correction is invisible
        // on every axis; anything larger is a real jump worth a banner.
        const val TOLERANCE_MS = 5 * 60_000L
        const val JUMP_THRESHOLD_MS = 5 * 60_000L
    }
}

/** Read-only, no permission needed. auto_time=0 was the measured root cause of
 *  the 15-month skew — surface it, don't just infer it. */
fun isAutoTimeEnabled(context: Context): Boolean =
    Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 0) == 1

fun systemBootCount(context: Context): Long =
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toLong()
