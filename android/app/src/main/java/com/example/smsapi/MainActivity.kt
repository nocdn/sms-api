package com.example.smsapi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 101
    private val networkExecutor = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var logsText: TextView
    private lateinit var backendUrlInput: EditText
    private lateinit var saveBackendUrlBtn: Button
    private lateinit var syncMessagesBtn: Button
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

        backendBaseUrl = BackendConfig.getBackendBaseUrl(this)
        backendUrlInput.setText(backendBaseUrl)
        AppLogStore.append(this, "MainActivity", "App opened")

        saveBackendUrlBtn.setOnClickListener {
            saveBackendUrl()
        }

        syncMessagesBtn.setOnClickListener {
            syncMessages(showToast = true)
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
            appendLine("SMS Forwarding")
            appendLine()
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
            appendLine("Health details:")
            appendLine(backendHealthDetail)
            appendLine()
            appendLine("Last checked:")
            appendLine(formatLastChecked())
            appendLine()
            appendLine("Sync status:")
            appendLine(
                when (syncStatus) {
                    SyncStatus.NOT_RUN -> "Not run yet"
                    SyncStatus.SYNCING -> "Syncing phone inbox to backend"
                    SyncStatus.SUCCESS -> "Synced successfully"
                    SyncStatus.FAILED -> "Sync failed"
                }
            )
            appendLine()
            appendLine("Sync details:")
            appendLine(syncDetail)
            appendLine()
            appendLine("Last sync:")
            appendLine(formatLastSync())
            appendLine()
            appendLine("Synced message count:")
            appendLine(lastSyncedCount.toString())
            appendLine()
            appendLine("Incoming SMS messages are sent to your backend automatically.")
            appendLine("Use Sync to make the backend exactly match your phone inbox.")
        }

        syncMessagesBtn.isEnabled = backendBaseUrl.isNotBlank() && syncStatus != SyncStatus.SYNCING
    }

    private fun renderLogs() {
        logsText.text = AppLogStore.getText(this)
    }

    private fun saveBackendUrl() {
        val normalizedUrl = BackendConfig.normalizeBackendBaseUrl(backendUrlInput.text.toString())

        if (!BackendConfig.isValidBackendBaseUrl(normalizedUrl)) {
            Toast.makeText(this, "Enter a valid http(s) backend URL", Toast.LENGTH_SHORT).show()
            return
        }

        BackendConfig.saveBackendBaseUrl(this, normalizedUrl)
        AppLogStore.append(this, "MainActivity", "Saved backend URL: $normalizedUrl")
        backendBaseUrl = normalizedUrl
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
        backendUrlInput.setText(normalizedUrl)
        renderStatus()

        if (normalizedUrl.isBlank()) {
            Toast.makeText(this, "SMS forwarding disabled", Toast.LENGTH_SHORT).show()
            return
        }

        checkBackendHealth(showToast = true)
    }

    private fun checkBackendHealth(showToast: Boolean) {
        val healthUrl = BackendConfig.buildHealthUrl(backendBaseUrl)

        if (healthUrl.isBlank()) {
            backendHealthStatus = BackendHealthStatus.NOT_CONFIGURED
            backendHealthDetail = "Health check disabled"
            lastHealthCheckAt = null
            renderStatus()
            return
        }

        backendHealthStatus = BackendHealthStatus.CHECKING
        backendHealthDetail = "Calling $healthUrl"
        renderStatus()

        networkExecutor.execute {
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

                if (showToast) {
                    val message = if (result.isHealthy) {
                        "Backend URL saved and health check passed"
                    } else {
                        "Backend URL saved, but /health failed"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
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

        syncStatus = SyncStatus.SYNCING
        syncDetail = "Reading the phone inbox and replacing backend messages"
        renderStatus()

        networkExecutor.execute {
            val result = SmsSyncManager.syncInboxToBackend(applicationContext, backendBaseUrl)

            runOnUiThread {
                syncStatus = if (result.success) SyncStatus.SUCCESS else SyncStatus.FAILED
                syncDetail = result.detail
                lastSyncAt = System.currentTimeMillis()
                lastSyncedCount = result.syncedCount
                AppLogStore.append(this, "MainActivity", "Manual sync finished: ${result.detail}")
                renderStatus()
                renderLogs()

                if (showToast) {
                    val message = if (result.success) {
                        "Synced ${result.syncedCount} messages"
                    } else {
                        "Sync failed"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun formatLastChecked(): String {
        val timestamp = lastHealthCheckAt ?: return "Not checked yet"
        return DateFormat.getDateTimeInstance().format(Date(timestamp))
    }

    private fun formatLastSync(): String {
        val timestamp = lastSyncAt ?: return "Not synced yet"
        return DateFormat.getDateTimeInstance().format(Date(timestamp))
    }

    override fun onDestroy() {
        networkExecutor.shutdownNow()
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
