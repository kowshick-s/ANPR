package com.vehiclegate.anpr

import android.app.Application
import com.vehiclegate.anpr.data.AppDatabase
import com.vehiclegate.anpr.data.SettingsStore

class AnprApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val settingsStore: SettingsStore by lazy { SettingsStore(this) }
    val parking: ParkingDesk by lazy { ParkingDesk(this) }

    override fun onCreate() {
        super.onCreate()
        applyTheme(settingsStore.settings().themeMode)
    }

    companion object {
        fun applyTheme(mode: Int) = androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
