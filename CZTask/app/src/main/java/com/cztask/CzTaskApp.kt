package com.cztask

import android.app.Application
import android.os.SystemClock

class CzTaskApp : Application() {
    override fun onCreate() {
        appStartUptimeMillis = SystemClock.uptimeMillis()
        super.onCreate()
        ServiceLocator.init(this)
    }

    companion object {
        /** Cold-start anchor for the CZTASK_BENCH log (step-3 stack benchmark). */
        var appStartUptimeMillis: Long = 0L
            private set
    }
}
