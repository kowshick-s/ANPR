package com.vehiclegate.anpr

import android.app.Application
import com.vehiclegate.anpr.data.AppDatabase
import com.vehiclegate.anpr.data.SettingsStore

class AnprApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val settingsStore: SettingsStore by lazy { SettingsStore(this) }
}
