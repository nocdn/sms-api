package com.example.smsapi

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SmsWorkScheduler {

    private const val ONE_TIME_WORK_NAME = "sms-sync-one-time"
    private const val PERIODIC_WORK_NAME = "sms-sync-periodic"

    fun scheduleOneTimeSync(context: Context, reason: String) {
        val request = OneTimeWorkRequestBuilder<SmsSyncWorker>()
            .setConstraints(defaultConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                SmsQueueState.BACKOFF_BASE_MS,
                TimeUnit.MILLISECONDS,
            )
            .addTag("sms-sync")
            .build()

        AppLogStore.append(context, "Work", "Enqueue one-time sync ($reason)")
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun ensurePeriodicSync(context: Context, backendBaseUrl: String) {
        if (backendBaseUrl.isBlank()) {
            cancelPeriodicSync(context)
            return
        }

        val request = PeriodicWorkRequestBuilder<SmsSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(defaultConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                SmsQueueState.BACKOFF_BASE_MS,
                TimeUnit.MILLISECONDS,
            )
            .addTag("sms-sync")
            .build()

        AppLogStore.append(context, "Work", "Ensure periodic sync")
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelPeriodicSync(context: Context) {
        AppLogStore.append(context, "Work", "Cancel periodic sync")
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    private fun defaultConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
