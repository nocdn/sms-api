package com.example.smsapi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
    private lateinit var backendUrlInput: EditText
    private lateinit var saveBackendUrlBtn: Button

    private var backendBaseUrl = ""
    private var backendHealthStatus = BackendHealthStatus.NOT_CONFIGURED
    private var backendHealthDetail = "No health check has been run yet"
    private var lastHealthCheckAt: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        backendUrlInput = findViewById(R.id.backendUrlInput)
        saveBackendUrlBtn = findViewById(R.id.saveBackendUrlBtn)

        backendBaseUrl = BackendConfig.getBackendBaseUrl(this)
        backendUrlInput.setText(backendBaseUrl)

        saveBackendUrlBtn.setOnClickListener {
            saveBackendUrl()
        }

        checkPermissions()
        renderStatus()

    }

    override fun onResume() {
        super.onResume()

        if (backendBaseUrl.isNotBlank() && backendHealthStatus != BackendHealthStatus.CHECKING) {
            checkBackendHealth(showToast = false)
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECEIVE_SMS)

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
            appendLine("Incoming SMS messages are sent to your backend automatically.")
        }
    }

    private fun saveBackendUrl() {
        val normalizedUrl = BackendConfig.normalizeBackendBaseUrl(backendUrlInput.text.toString())

        if (!BackendConfig.isValidBackendBaseUrl(normalizedUrl)) {
            Toast.makeText(this, "Enter a valid http(s) backend URL", Toast.LENGTH_SHORT).show()
            return
        }

        BackendConfig.saveBackendBaseUrl(this, normalizedUrl)
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
            val result = try {
                val connection = (URL(healthUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    doInput = true
                }

                try {
                    val responseCode = connection.responseCode
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

    private fun formatLastChecked(): String {
        val timestamp = lastHealthCheckAt ?: return "Not checked yet"
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

    private data class HealthCheckResult(
        val isHealthy: Boolean,
        val detail: String,
    )
}
