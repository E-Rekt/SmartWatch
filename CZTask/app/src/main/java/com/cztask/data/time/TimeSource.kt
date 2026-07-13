package com.cztask.data.time

import android.os.SystemClock
import java.time.ZoneId

/** Everything that touches "now" takes a TimeSource — that's what makes the
 *  15-month-jump scenario a unit test instead of a field incident. */
interface TimeSource {
    fun nowUtcMillis(): Long
    fun zone(): ZoneId

    /** Monotonic, includes deep sleep, immune to wall-clock changes. Resets on
     *  reboot — pair with a boot count when persisting anchors. */
    fun elapsedRealtimeMillis(): Long
}

object SystemTimeSource : TimeSource {
    override fun nowUtcMillis() = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
    override fun elapsedRealtimeMillis() = SystemClock.elapsedRealtime()
}
