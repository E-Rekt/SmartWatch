package com.cztask

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cztask.data.time.isAutoTimeEnabled
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tripwire, not a test of our code: this device shipped to us with auto_time=0
 * and a clock 15 months slow. If either regresses, every wall-clock feature is
 * silently wrong — fail the suite loudly instead.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTimeSanityTest {

    @Test fun deviceClockIsSane() {
        val year = LocalDate.now(ZoneId.systemDefault()).year
        assertTrue(
            "Watch clock reads year $year — it has rotted again. " +
                "Fix: adb shell cmd alarm set-time <epoch-millis>",
            year >= 2026,
        )
    }

    @Test fun autoTimeIsStillEnabled() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(
            "Settings.Global.auto_time is OFF — the measured root cause of the " +
                "15-month clock skew. Fix: adb shell settings put global auto_time 1",
            isAutoTimeEnabled(ctx),
        )
    }
}
