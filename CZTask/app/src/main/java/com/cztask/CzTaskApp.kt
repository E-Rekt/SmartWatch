package com.cztask

import android.app.Application

class CzTaskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
