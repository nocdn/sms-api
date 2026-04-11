package com.example.smsapi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

class ServerForegroundService : Service() {

    private var server: PhoneHttpServer? = null
    private var startedAtMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification("Starting SMS API server")
        ensureServerStarted()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppLogStore.append(this, TAG, "Stop requested")
                stopServer()
                removeForegroundState()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                ensureServerStarted()
                startForegroundWithNotification("Serving SMS API on port ${ApiContract.SERVER_PORT}")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun ensureServerStarted() {
        if (server != null) {
            return
        }

        startedAtMillis = System.currentTimeMillis()
        val nextServer = PhoneHttpServer(applicationContext, startedAtMillis)

        try {
            nextServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = nextServer
            AppLogStore.append(this, TAG, "HTTP server listening on port ${ApiContract.SERVER_PORT}")
            startForegroundWithNotification("Serving SMS API on port ${ApiContract.SERVER_PORT}")
        } catch (error: IOException) {
            AppLogStore.append(this, TAG, "Failed to start HTTP server: ${error.message ?: error::class.java.simpleName}")
            removeForegroundState()
            stopSelf()
        }
    }

    private fun stopServer() {
        server?.stop()
        if (server != null) {
            AppLogStore.append(this, TAG, "HTTP server stopped")
        }
        server = null
    }

    private fun startForegroundWithNotification(contentText: String) {
        createNotificationChannel()

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, pendingFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("SMS API server")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun removeForegroundState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "SMS API Server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent notification for the phone-hosted SMS API server"
        }

        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ServerForegroundService"
        private const val ACTION_START = "com.example.smsapi.action.START_SERVER"
        private const val ACTION_STOP = "com.example.smsapi.action.STOP_SERVER"
        private const val CHANNEL_ID = "sms-api-server"
        private const val NOTIFICATION_ID = 6770

        fun start(context: Context) {
            val intent = Intent(context, ServerForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ServerForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun healthUrl(): String {
            return "http://127.0.0.1:${ApiContract.SERVER_PORT}${ApiContract.HEALTH_PATH}"
        }

        fun messagesUrl(): String {
            return "http://127.0.0.1:${ApiContract.SERVER_PORT}${ApiContract.MESSAGES_PATH}"
        }
    }
}
