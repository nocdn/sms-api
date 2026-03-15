package com.example.smsapi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

class SmsSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val context = applicationContext
        val backendBaseUrl = BackendConfig.getBackendBaseUrl(context)
        if (backendBaseUrl.isBlank()) {
            AppLogStore.append(context, TAG, "Worker skipped: backend URL not configured")
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val snapshot = SmsQueueStore.readState(context)
        val eligibleQueue = snapshot.queued.filter { it.nextEligibleAt <= now }

        val hasReadSmsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS,
        ) == PackageManager.PERMISSION_GRANTED

        if (eligibleQueue.isEmpty() && !hasReadSmsPermission) {
            if (snapshot.queued.isNotEmpty()) {
                AppLogStore.append(context, TAG, "Worker waiting: ${snapshot.queued.size} queued messages not yet eligible")
                return Result.retry()
            }

            AppLogStore.append(context, TAG, "Worker idle: no queued messages and READ_SMS missing")
            return Result.success()
        }

        if (eligibleQueue.isEmpty() && snapshot.queued.isNotEmpty()) {
            AppLogStore.append(context, TAG, "Worker waiting: ${snapshot.queued.size} queued messages not yet eligible")
            return Result.retry()
        }

        val inboxMessages = if (hasReadSmsPermission) {
            SmsSyncManager.loadInboxMessages(context)
        } else {
            emptyList()
        }

        val queuedMessages = eligibleQueue.map { message ->
            SmsSyncManager.PhoneMessage(
                address = message.address,
                body = message.body,
                receivedAt = message.receivedAt,
            )
        }

        val mergedMessages = SmsSyncManager.mergeMessages(inboxMessages, queuedMessages)

        AppLogStore.append(
            context,
            TAG,
            "Worker syncing ${mergedMessages.size} messages (${queuedMessages.size} queued, ${inboxMessages.size} inbox)",
        )

        val syncResult = SmsSyncManager.syncMessagesToBackend(
            context = context,
            backendBaseUrl = backendBaseUrl,
            messages = mergedMessages,
        )

        val queuedKeys = eligibleQueue.map { it.key }.toSet()

        return if (syncResult.success) {
            if (queuedKeys.isNotEmpty()) {
                SmsQueueStore.markProcessed(context, queuedKeys, now)
            }
            AppLogStore.append(context, TAG, "Worker sync success: ${syncResult.detail}")
            Result.success()
        } else {
            if (queuedKeys.isNotEmpty()) {
                SmsQueueStore.markAttempted(context, queuedKeys, now)
            }
            AppLogStore.append(context, TAG, "Worker sync failed: ${syncResult.detail}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SmsSyncWorker"
    }
}
