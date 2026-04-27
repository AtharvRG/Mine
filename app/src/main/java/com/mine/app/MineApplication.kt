package com.mine.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MineApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
