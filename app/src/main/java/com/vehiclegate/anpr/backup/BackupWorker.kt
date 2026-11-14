package com.vehiclegate.anpr.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vehiclegate.anpr.AnprApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Every hour: email the parked list (PDF) + today's CSV, only if something changed since the last one. */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as AnprApp
        val store = BackupStore(app)
        if (!store.enabled || !store.ready) return Result.success()
        return when (sendBackup(app, force = false)) {
            null, "same" -> Result.success()
            else -> Result.retry()
        }
    }

    companion object {
        private const val NAME = "email_backup"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Sends now. Returns null when sent, "same" when nothing changed (not forced), else the error. */
        suspend fun sendBackup(app: AnprApp, force: Boolean): String? {
            val store = BackupStore(app)
            if (!store.ready) return "Set your Gmail and app password first"
            val r = BackupReport(app).build()
            if (!force && r.fingerprint == store.lastFingerprint) return "same"
            val time = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())
            return try {
                Mailer.send(store.gmail, store.appPassword, store.receivers(),
                    "Parking backup – $time – ${r.parked.size} parked, ₹${r.collected} today",
                    r.summary, listOf(r.pdf, r.csv))
                store.lastFingerprint = r.fingerprint
                store.lastResult = "Last backup sent $time ✓"
                null
            } catch (e: Exception) {
                val msg = when {
                    e.javaClass.simpleName.contains("Authentication") -> "Gmail refused the login – check the address and app password"
                    else -> e.message ?: e.javaClass.simpleName
                }
                store.lastResult = "Backup failed $time: $msg"
                msg
            }
        }
    }
}
