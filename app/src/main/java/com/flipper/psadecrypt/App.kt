package com.flipper.psadecrypt

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("dynamic_color", true)) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
    }
}
