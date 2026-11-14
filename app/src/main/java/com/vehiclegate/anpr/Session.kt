package com.vehiclegate.anpr

import android.content.Context
import com.vehiclegate.anpr.core.PinHash
import com.vehiclegate.anpr.data.AppUser
import com.vehiclegate.anpr.data.Role
import java.util.UUID

/**
 * Who is using this device. The logged-in user stays logged in (also after a restart) until
 * "Switch user". Admin-only actions ask for an admin PIN when someone else is logged in.
 */
class Session(private val app: AnprApp) {
    private val prefs = app.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Volatile var user: AppUser? = null
        private set

    val isAdmin get() = user?.role == Role.ADMIN
    val isTv get() = user?.role == Role.TV
    /** Name recorded on check-ins / check-outs. */
    val who get() = user?.name

    /** Restores the last logged-in user of this device (null = show the login screen). */
    suspend fun restore(): AppUser? {
        val id = prefs.getString("userId", null) ?: return null
        val u = app.db.users().get(id)?.takeIf { it.active }
        user = u
        return u
    }

    suspend fun needsSetup() = app.db.users().adminCount() == 0

    suspend fun login(u: AppUser, pin: String): Boolean {
        if (!u.active || !PinHash.matches(pin, u.salt, u.pinHash)) return false
        user = u
        prefs.edit().putString("userId", u.id).apply()
        return true
    }

    fun logout() {
        user = null
        prefs.edit().remove("userId").apply()
    }

    /** Any active admin with this PIN (for admin-only actions while staff are logged in). */
    suspend fun adminForPin(pin: String): AppUser? =
        app.db.users().active().firstOrNull { it.role == Role.ADMIN && PinHash.matches(pin, it.salt, it.pinHash) }

    /** Creates / updates a user with a new PIN. */
    suspend fun saveUser(existing: AppUser?, name: String, role: String, pin: String?): AppUser {
        val salt = if (pin != null) PinHash.newSalt() else existing!!.salt
        val u = AppUser(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim(), role = role,
            pinHash = if (pin != null) PinHash.hash(pin, salt) else existing!!.pinHash,
            salt = salt, active = existing?.active ?: true,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        app.db.users().upsert(u)
        if (user?.id == u.id) user = u
        return u
    }

    suspend fun setActive(u: AppUser, active: Boolean) {
        app.db.users().upsert(u.copy(active = active))
    }

    // ---- admin PIN reset code (shown once when the first admin is created)

    fun newResetCode(): String {
        val code = PinHash.newResetCode()
        val salt = PinHash.newSalt()
        prefs.edit().putString("resetSalt", salt)
            .putString("resetHash", PinHash.hash(PinHash.normalizeCode(code), salt)).apply()
        return code
    }

    fun resetCodeMatches(code: String): Boolean {
        val salt = prefs.getString("resetSalt", null) ?: return false
        val hash = prefs.getString("resetHash", null) ?: return false
        return PinHash.matches(PinHash.normalizeCode(code), salt, hash)
    }
}
