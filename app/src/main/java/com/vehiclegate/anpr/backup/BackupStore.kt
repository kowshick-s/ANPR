package com.vehiclegate.anpr.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Email backup settings. The Gmail app password is kept in encrypted storage on this device only
 * (never in the settings export or the cloud copy).
 */
class BackupStore(context: Context) {
    private val plain = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val secret: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            "backup_secret", MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("backup_secret_fallback", Context.MODE_PRIVATE)
    }

    var enabled: Boolean
        get() = plain.getBoolean("enabled", false)
        set(v) = plain.edit().putBoolean("enabled", v).apply()
    /** Gmail address that sends (and receives) the backup */
    var gmail: String
        get() = plain.getString("gmail", "") ?: ""
        set(v) = plain.edit().putString("gmail", v.trim()).apply()
    /** More receivers, comma separated (optional) */
    var alsoTo: String
        get() = plain.getString("alsoTo", "") ?: ""
        set(v) = plain.edit().putString("alsoTo", v.trim()).apply()
    var appPassword: String
        get() = secret.getString("appPassword", "") ?: ""
        set(v) = secret.edit().putString("appPassword", v.filter { !it.isWhitespace() }).apply()

    var lastFingerprint: String
        get() = plain.getString("lastFp", "") ?: ""
        set(v) = plain.edit().putString("lastFp", v).apply()
    var lastResult: String
        get() = plain.getString("lastResult", "") ?: ""
        set(v) = plain.edit().putString("lastResult", v).apply()

    val ready get() = gmail.contains("@") && appPassword.length >= 16
    fun receivers(): List<String> =
        (listOf(gmail) + alsoTo.split(',', ';', ' ')).map { it.trim() }.filter { it.contains("@") }.distinct()
}
