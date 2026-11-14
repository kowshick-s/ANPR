package com.vehiclegate.anpr.admin

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager

/**
 * Device administrator: Android won't uninstall the app until this is switched off in Settings
 * (stops accidental uninstalls).
 */
class GateAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Switching this off allows the parking app to be uninstalled. Parking records on this device may be lost " +
            "unless the cloud copy is on."
}

/** After a power cut / restart: open the app again on a locked (kiosk) device. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!DeviceProtection.isKiosk(context)) return
        context.startActivity(Intent(context, com.vehiclegate.anpr.ui.LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Uninstall protection.
 *  1. Kiosk (device owner): uninstall blocked, device locked to the app, opens after restart.
 *     Set up once from a laptop:  adb shell dpm set-device-owner com.vehiclegate.anpr/.admin.GateAdminReceiver
 *  2. Device administrator: uninstall needs switching this off first.
 */
object DeviceProtection {
    const val ADB_COMMAND = "adb shell dpm set-device-owner com.vehiclegate.anpr/.admin.GateAdminReceiver"
    private const val PREF = "protection"

    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    fun admin(c: Context) = ComponentName(c, GateAdminReceiver::class.java)

    fun isDeviceOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)
    fun isAdminActive(c: Context) = dpm(c).isAdminActive(admin(c))
    fun isKiosk(c: Context) = isDeviceOwner(c) && c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("kiosk", false)

    /** Screen that asks the user to switch on "device administrator" for this app. */
    fun askAdmin(a: Activity) {
        a.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(a))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Protects the parking app from being uninstalled by mistake."))
    }

    fun removeAdmin(c: Context) { if (isAdminActive(c) && !isDeviceOwner(c)) dpm(c).removeActiveAdmin(admin(c)) }

    /** Kiosk on/off (device owner only): uninstall block, lock to the app, open on start. */
    fun setKiosk(c: Context, on: Boolean) {
        if (!isDeviceOwner(c)) return
        val d = dpm(c); val a = admin(c)
        d.setUninstallBlocked(a, c.packageName, on)
        d.setLockTaskPackages(a, if (on) arrayOf(c.packageName) else emptyArray())
        val home = ComponentName(c, "com.vehiclegate.anpr.ui.KioskHome")
        c.packageManager.setComponentEnabledSetting(home,
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        if (on) {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
            }
            d.addPersistentPreferredActivity(a, filter, home)
        } else d.clearPackagePersistentPreferredActivities(a, c.packageName)
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("kiosk", on).apply()
    }

    /** Hands the device back (allows uninstall again). */
    fun releaseDeviceOwner(c: Context) {
        if (!isDeviceOwner(c)) return
        setKiosk(c, false)
        dpm(c).clearDeviceOwnerApp(c.packageName)
    }

    /** Call from every screen's onResume: keeps the device locked to the app in kiosk mode. */
    fun enterLockTask(a: Activity) {
        if (!isKiosk(a)) return
        try { a.startLockTask() } catch (_: Exception) {}
    }

    fun leaveLockTask(a: Activity) {
        try { a.stopLockTask() } catch (_: Exception) {}
    }
}
