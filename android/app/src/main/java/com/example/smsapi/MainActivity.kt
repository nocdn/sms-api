package com.example.smsapi

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 101

    private lateinit var statusText: TextView
    private lateinit var logsText: TextView
    private lateinit var backendUrlInput: EditText
    private lateinit var saveBackendUrlBtn: Button
    private lateinit var syncMessagesBtn: Button
    private lateinit var clearLogsBtn: Button
    private lateinit var saveLogsBtn: Button
    private val logsRefreshHandler = Handler(Looper.getMainLooper())
    private val logsRefreshRunnable = object : Runnable {
        override fun run() {
            renderLogs()
            logsRefreshHandler.postDelayed(this, 1000)
        }
    }

    private var backendBaseUrl = ""
    private var backendHealthStatus = BackendHealthStatus.NOT_CONFIGURED
    private var backendHealthDetail = "No health check has been run yet"
    private var lastHealthCheckAt: Long? = null
    private var syncStatus = SyncStatus.NOT_RUN
    private var syncDetail = "No sync has been run yet"
    private var lastSyncAt: Long? = null
    private var lastSyncedCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logsText = findViewById(R.id.logsText)
        backendUrlInput = findViewById(R.id.backendUrlInput)
        saveBackendUrlBtn = findViewById(R.id.saveBackendUrlBtn)
        syncMessagesBtn = findViewById(R.id.syncMessagesBtn)
        clearLogsBtn = findViewById(R.id.clearLogsBtn)
        saveLogsBtn = findViewById(R.id.saveLogsBtn)

        backendBaseUrl = BackendConfig.getBackendBaseUrl(this)
        backendUrlInput.setText(backendBaseUrl)
        AppLogStore.append(this, "MainActivity", "App opened")

        if (backendBaseUrl.isNotBlank()) {
            SmsWorkScheduler.ensurePeriodicSync(this, backendBaseUrl)
            SmsWorkScheduler.scheduleOneTimeSync(this, "app-open")
        }

        saveBackendUrlBtn.setOnClickListener {
            saveBackendUrl(backendUrlInput.text.toString())
        }

        syncMessagesBtn.setOnClickListener {
            syncMessages(showToast = true)
        }

        clearLogsBtn.setOnClickListener {
            clearLogs()
        }

        saveLogsBtn.setOnClickListener {
            saveLogs()
        }

        checkPermissions()
        renderStatus()
        renderLogs()
    }

    override fun onStart() {
        super.onStart()
        logsRefreshHandler.post(logsRefreshRunnable)
    }

    override fun onResume() {
        super.onResume()

        if (backendBaseUrl.isNotBlank() && backendHealthStatus != BackendHealthStatus.CHECKING) {
            checkBackendHealth(showToast = false)
        }
    }

    override fun onStop() {
        logsRefreshHandler.removeCallbacks(logsRefreshRunnable)
        super.onStop()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
        )

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun renderStatus() {
        statusText.text = buildString {
            appendLine("Status:")
            appendLine(
                when (backendHealthStatus) {
                    BackendHealthStatus.NOT_CONFIGURED -> "Waiting for backend URL"
                    BackendHealthStatus.CHECKING -> "Checking backend health"
                    BackendHealthStatus.HEALTHY -> "Ready to forward incoming SMS"
                    BackendHealthStatus.UNHEALTHY -> "Backend health check failed"
                }
            )
            appendLine()
            appendLine("Backend URL:")
            appendLine(if (backendBaseUrl.isBlank()) "Not configured" else backendBaseUrl)
            appendLine()
            appendLine("Health check:")
            appendLine(
                when (backendHealthStatus) {
                    BackendHealthStatus.NOT_CONFIGURED -> "Not configured"
                    BackendHealthStatus.CHECKING -> "Checking /health..."
                    BackendHealthStatus.HEALTHY -> "Working"
                    BackendHealthStatus.UNHEALTHY -> "Not working"
                }
            )
            appendLine()
            appendLine("Last checked:")
            appendLine(formatLastChecked())
        }

        syncMessagesBtn.isEnabled = backendBaseUrl.isNotBlank() && syncStatus != SyncStatus.SYNCING
        saveBackendUrlBtn.isEnabled = syncStatus != SyncStatus.SYNCING
    }

    private fun renderLogs() {
        logsText.text = AppLogStore.getText(this)
    }

    private fun clearLogs() {
        AppLogStore.clear(this)
        renderLogs()
        Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
    }

    private fun saveLogs() {
        val logContent = AppLogStore.getText(this)
        if (logContent == "No logs yet") {
            Toast.makeText(this, "No logs to save", Toast.LENGTH_SHORT).show()
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "sms-api-logs_$timestamp.log"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(logContent.toByteArray()) }
                    Toast.makeText(this, "Logs saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Failed to save logs", Toast.LENGTH_SHORT).show()
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                file.writeText(logContent)
                Toast.makeText(this, "Logs saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
            }
            AppLogStore.append(this, "MainActivity", "Logs saved to Downloads/$fileName")
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save logs: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBackendUrl(inputValue: String, onSaved: (() -> Unit)? = null) {
        val normalizedUrl = BackendConfig.normalizeBackendBaseUrl(inputValue)

        if (!BackendConfig.isValidBackendBaseUrl(normalizedUrl)) {
            Toast.makeText(this, "Enter a valid http(s) backend URL", Toast.LENGTH_SHORT).show()
            return
        }

        BackendConfig.saveBackendBaseUrl(this, normalizedUrl)
        AppLogStore.append(this, "MainActivity", "Saved backend URL: $normalizedUrl")
        backendBaseUrl = normalizedUrl
        backendUrlInput.setText(normalizedUrl)
        backendUrlInput.setSelection(backendUrlInput.text.length)
        backendHealthStatus = if (normalizedUrl.isBlank()) {
            BackendHealthStatus.NOT_CONFIGURED
        } else {
            BackendHealthStatus.CHECKING
        }
        backendHealthDetail = if (normalizedUrl.isBlank()) {
            "Health check disabled"
        } else {
            "Waiting to call /health"
        }
        lastHealthCheckAt = null
        renderStatus()

        if (normalizedUrl.isBlank()) {
            SmsWorkScheduler.cancelPeriodicSync(this)
            onSaved?.invoke()
            Toast.makeText(this, "SMS forwarding disabled", Toast.LENGTH_SHORT).show()
            return
        }

        SmsWorkScheduler.ensurePeriodicSync(this, normalizedUrl)
        SmsWorkScheduler.scheduleOneTimeSync(this, "backend-configured")

        checkBackendHealth(showToast = true, onComplete = onSaved)
    }

    private fun checkBackendHealth(showToast: Boolean, onComplete: (() -> Unit)? = null) {
        val healthUrl = BackendConfig.buildHealthUrl(backendBaseUrl)

        if (healthUrl.isBlank()) {
            backendHealthStatus = BackendHealthStatus.NOT_CONFIGURED
            backendHealthDetail = "Health check disabled"
            lastHealthCheckAt = null
            renderStatus()
            onComplete?.invoke()
            return
        }

        backendHealthStatus = BackendHealthStatus.CHECKING
        backendHealthDetail = "Calling $healthUrl"
        renderStatus()

        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            AppLogStore.append(this, "Health", "OUT GET /health -> $healthUrl")
            val result = try {
                val connection = (URL(healthUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    doInput = true
                }

                try {
                    val responseCode = connection.responseCode
                    AppLogStore.append(this, "Health", "IN  GET /health <- HTTP $responseCode ${connection.responseMessage}")
                    if (responseCode in 200..299) {
                        HealthCheckResult(
                            isHealthy = true,
                            detail = "HTTP $responseCode ${connection.responseMessage}",
                        )
                    } else {
                        HealthCheckResult(
                            isHealthy = false,
                            detail = "HTTP $responseCode ${connection.responseMessage}",
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                AppLogStore.append(this, "Health", "ERR GET /health <- ${error.message ?: error::class.java.simpleName}")
                HealthCheckResult(
                    isHealthy = false,
                    detail = error.message ?: error::class.java.simpleName,
                )
            }

            runOnUiThread {
                backendHealthStatus = if (result.isHealthy) {
                    BackendHealthStatus.HEALTHY
                } else {
                    BackendHealthStatus.UNHEALTHY
                }
                backendHealthDetail = result.detail
                lastHealthCheckAt = System.currentTimeMillis()

                renderStatus()
                onComplete?.invoke()

                if (showToast) {
                    val message = if (result.isHealthy) {
                        "Backend URL saved and health check passed"
                    } else {
                        "Backend URL saved, but /health failed"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
            executor.shutdown()
        }
    }

    private fun syncMessages(showToast: Boolean) {
        AppLogStore.append(this, "MainActivity", "Manual sync requested")
        val missingPermissions = listOf(Manifest.permission.READ_SMS).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
            syncStatus = SyncStatus.FAILED
            syncDetail = "Grant SMS read permission to sync the phone inbox"
            AppLogStore.append(this, "MainActivity", "Manual sync blocked: READ_SMS permission missing")
            renderStatus()
            if (showToast) {
                Toast.makeText(this, "Grant SMS read permission to sync", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (backendBaseUrl.isBlank()) {
            syncStatus = SyncStatus.FAILED
            syncDetail = "Save a backend URL before syncing"
            AppLogStore.append(this, "MainActivity", "Manual sync blocked: backend URL not configured")
            renderStatus()
            if (showToast) {
                Toast.makeText(this, "Save a backend URL before syncing", Toast.LENGTH_SHORT).show()
            }
            return
        }

        syncStatus = SyncStatus.SUCCESS
        syncDetail = "Scheduled background sync"
        renderStatus()

        SmsWorkScheduler.scheduleOneTimeSync(this, "manual")
        AppLogStore.append(this, "MainActivity", "Manual sync enqueued")
        lastSyncAt = System.currentTimeMillis()
        lastSyncedCount = 0
        renderStatus()
        renderLogs()

        if (showToast) {
            Toast.makeText(this, "Background sync scheduled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatLastChecked(): String {
        val timestamp = lastHealthCheckAt ?: return "Not checked yet"
        return DateFormat.getDateTimeInstance().format(Date(timestamp))
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private enum class BackendHealthStatus {
        NOT_CONFIGURED,
        CHECKING,
        HEALTHY,
        UNHEALTHY,
    }

    private enum class SyncStatus {
        NOT_RUN,
        SYNCING,
        SUCCESS,
        FAILED,
    }

    private data class HealthCheckResult(
        val isHealthy: Boolean,
        val detail: String,
    )
}
