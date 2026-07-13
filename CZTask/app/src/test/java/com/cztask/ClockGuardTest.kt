package com.cztask

import com.cztask.data.time.ClockGuard
import com.cztask.data.time.ClockStatus
import com.cztask.data.time.LongStore
import com.cztask.data.time.TimeSource
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeTime(var wall: Long, var elapsed: Long) : TimeSource {
    override fun nowUtcMillis() = wall
    override fun zone(): ZoneId = ZoneId.of("UTC")
    override fun elapsedRealtimeMillis() = elapsed
}

private class MapStore : LongStore {
    val map = HashMap<String, Long>()
    override fun get(key: String, def: Long) = map.getOrDefault(key, def)
    override fun put(key: String, value: Long) { map[key] = value }
}

class ClockGuardTest {

    private val buildFloor = 1_000_000_000_000L // arbitrary fixed floor
    private var boot = 5L

    private fun guard(time: FakeTime, store: MapStore = MapStore()) =
        ClockGuard(store, time, buildFloor, bootCount = { boot })

    @Test fun `clock before build floor is provably wrong`() {
        val g = guard(FakeTime(wall = buildFloor - 1, elapsed = 10_000))
        assertEquals(ClockStatus.BEFORE_BUILD, g.checkAndAdvance())
    }

    @Test fun `normal forward march is OK and advances high water`() {
        val store = MapStore()
        val time = FakeTime(wall = buildFloor + 1_000, elapsed = 10_000)
        val g = guard(time, store)
        assertEquals(ClockStatus.OK, g.checkAndAdvance())
        assertEquals(buildFloor + 1_000, store.map[ClockGuard.KEY_HIGH_WATER])
    }

    @Test fun `same-boot backward step reports JUMPED once then recovers`() {
        val store = MapStore()
        val time = FakeTime(wall = buildFloor + 3_600_000, elapsed = 10_000)
        val g = guard(time, store)
        g.checkAndAdvance()
        time.wall -= 10 * 60_000       // corroborated correction: wall back, elapsed normal
        time.elapsed += 1_000
        assertEquals(ClockStatus.JUMPED, g.checkAndAdvance())
        time.wall += 1_000             // consistent march afterwards
        time.elapsed += 1_000
        assertEquals(ClockStatus.OK, g.checkAndAdvance())   // not sticky BEHIND
    }

    @Test fun `cross-boot backward clock is BEHIND_HIGH_WATER`() {
        val store = MapStore()
        val time = FakeTime(wall = buildFloor + 3_600_000, elapsed = 10_000_000)
        val g = guard(time, store)
        g.checkAndAdvance()
        boot += 1                      // no axis evidence across reboot
        time.wall -= 10 * 60_000
        time.elapsed = 30_000
        assertEquals(ClockStatus.BEHIND_HIGH_WATER, g.checkAndAdvance())
    }

    @Test fun `suspect forward jump does not poison the high-water mark`() {
        val store = MapStore()
        val time = FakeTime(wall = buildFloor + 3_600_000, elapsed = 10_000)
        val g = guard(time, store)
        g.checkAndAdvance()
        val honestMark = store.map[ClockGuard.KEY_HIGH_WATER]
        time.wall += 3 * 60 * 60_000L  // bogus +3h jump
        time.elapsed += 60_000L
        assertEquals(ClockStatus.JUMPED, g.checkAndAdvance())
        assertEquals(honestMark, store.map[ClockGuard.KEY_HIGH_WATER]) // not advanced
        time.wall += 60_000            // next consistent check ratifies the new time
        time.elapsed += 60_000
        assertEquals(ClockStatus.OK, g.checkAndAdvance())
        assertEquals(time.wall, store.map[ClockGuard.KEY_HIGH_WATER])
    }

    @Test fun `two minutes behind high water is within NTP tolerance`() {
        val store = MapStore()
        val time = FakeTime(wall = buildFloor + 3_600_000, elapsed = 10_000)
        val g = guard(time, store)
        g.checkAndAdvance()
        time.wall -= 2 * 60_000
        time.elapsed += 1_000
        // A small NTP step correction stays invisible on both axes:
        // not BEHIND (5-min tolerance) and not JUMPED (5-min drift threshold).
        assertEquals(ClockStatus.OK, g.checkAndAdvance())
    }

    @Test fun `forward jump beyond elapsed time is detected within same boot`() {
        val store = MapStore()
        val time = FakeTime(wall = buildFloor + 3_600_000, elapsed = 10_000)
        val g = guard(time, store)
        g.checkAndAdvance()
        time.wall += 3 * 60 * 60_000L  // wall +3h
        time.elapsed += 60_000L        // but only 1 min actually passed
        assertEquals(ClockStatus.JUMPED, g.checkAndAdvance())
    }

    @Test fun `doze is not a jump - both axes advance together`() {
        val store = MapStore()
        val time = FakeTime(wall = buildFloor + 3_600_000, elapsed = 10_000)
        val g = guard(time, store)
        g.checkAndAdvance()
        time.wall += 3 * 60 * 60_000L  // 3 hours of doze
        time.elapsed += 3 * 60 * 60_000L
        assertEquals(ClockStatus.OK, g.checkAndAdvance())
    }

    @Test fun `reboot invalidates the elapsed axis - no false jump`() {
        val store = MapStore()
        val time = FakeTime(wall = buildFloor + 3_600_000, elapsed = 10_000_000)
        val g = guard(time, store)
        g.checkAndAdvance()
        boot += 1                       // reboot
        time.wall += 5 * 60_000
        time.elapsed = 30_000           // elapsed reset
        assertEquals(ClockStatus.OK, g.checkAndAdvance())
    }
}
