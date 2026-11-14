package com.vehiclegate.anpr.core

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PINs and reset codes are never stored, only a salted PBKDF2 hash. */
object PinHash {
    private const val ITERATIONS = 20_000

    fun newSalt(): String {
        val b = ByteArray(16); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(), ITERATIONS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return key.joinToString("") { "%02x".format(it) }
    }

    fun matches(pin: String, salt: String, expected: String): Boolean {
        val h = hash(pin, salt)
        if (h.length != expected.length) return false
        var diff = 0
        for (i in h.indices) diff = diff or (h[i].code xor expected[i].code)
        return diff == 0
    }

    /** A PIN must be 4–6 digits. */
    fun validPin(pin: String) = pin.length in 4..6 && pin.all { it.isDigit() }

    /** One-time admin reset code, e.g. "K7QM-4XTP" (no 0/O/1/I to avoid confusion). */
    fun newResetCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        val s = (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
        return s.substring(0, 4) + "-" + s.substring(4)
    }

    fun normalizeCode(code: String) = code.uppercase().filter { it.isLetterOrDigit() }
}
