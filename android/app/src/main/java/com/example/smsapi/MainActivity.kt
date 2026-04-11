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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Enumeration
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 101
    private val statusRefreshHandler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var logsText: TextView
    private lateinit var startServerBtn: Button
    private lateinit var stopServerBtn: Button
    private lateinit var refreshStatusBtn: Button
    private lateinit var clearLogsBtn: Button
    private lateinit var saveLogsBtn: Button
    private val logsRefreshHandler = Handler(Looper.getMainLooper())
    private val logsRefreshRunnable = object : Runnable {
        override fun run() {
            renderLogs()
            logsRefreshHandler.postDelayed(this, 1000)
        }
    }

    private var serverHealthStatus = ServerHealthStatus.CHECKING
    private var serverHealthDetail = "No health check has been run yet"
    private var lastHealthCheckAt: Long? = null
    private var serverStartedAt: String? = null
    private var serverUptimeMs: Long? = null
    private var smsPermissionGranted = false
    private var appVersion = "Unknown"
    private var messageScope = "all_device_sms"
    private var localNetworkAddress: String? = null
    private var localNetworkMacAddress: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logsText = findViewById(R.id.logsText)
        startServerBtn = findViewById(R.id.startServerBtn)
        stopServerBtn = findViewById(R.id.stopServerBtn)
        refreshStatusBtn = findViewById(R.id.refreshStatusBtn)
        clearLogsBtn = findViewById(R.id.clearLogsBtn)
        saveLogsBtn = findViewById(R.id.saveLogsBtn)

        AppLogStore.append(this, "MainActivity", "App opened")
        appVersion = AppMetadata.versionName(this)
        refreshLocalNetworkDetails()

        startServerBtn.setOnClickListener {
            startServer()
        }

        stopServerBtn.setOnClickListener {
            stopServer()
        }

        refreshStatusBtn.setOnClickListener {
            checkServerHealth(showToast = true)
        }

        clearLogsBtn.setOnClickListener {
            clearLogs()
        }

        saveLogsBtn.setOnClickListener {
            saveLogs()
        }

        checkPermissions()
        ServerForegroundService.start(this)
        renderStatus()
        renderLogs()
        scheduleHealthRefresh(showToast = false)
    }

    override fun onStart() {
        super.onStart()
        logsRefreshHandler.post(logsRefreshRunnable)
    }

    override fun onResume() {
        super.onResume()
        refreshLocalNetworkDetails()
        checkServerHealth(showToast = false)
    }

    override fun onStop() {
        logsRefreshHandler.removeCallbacks(logsRefreshRunnable)
        statusRefreshHandler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private fun checkPermissions() {
        val permissions = listOf(Manifest.permission.READ_SMS)

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun renderStatus() {
        if (localNetworkAddress == null && localNetworkMacAddress == null) {
            refreshLocalNetworkDetails()
        }

        statusText.text = buildString {
            appendLine("Server:")
            appendLine(
                when (serverHealthStatus) {
                    ServerHealthStatus.CHECKING -> "Checking localhost health"
                    ServerHealthStatus.HEALTHY -> "Running"
                    ServerHealthStatus.UNHEALTHY -> "Not reachable"
                }
            )
            appendLine()
            appendLine("Port:")
            appendLine(ApiContract.SERVER_PORT.toString())
            appendLine()
            appendLine("Local network IP:")
            appendLine(localNetworkAddress ?: "Unavailable")
            appendLine()
            appendLine("MAC address:")
            appendLine(localNetworkMacAddress ?: "Unavailable")
            appendLine()
            appendLine("Health detail:")
            appendLine(serverHealthDetail)
            appendLine()
            appendLine("Started at:")
            appendLine(serverStartedAt ?: "Unknown")
            appendLine()
            appendLine("Uptime:")
            appendLine(formatUptime(serverUptimeMs))
            appendLine()
            appendLine("READ_SMS permission:")
            appendLine(if (smsPermissionGranted) "Granted" else "Missing")
            appendLine()
            appendLine("App version:")
            appendLine(appVersion)
            appendLine()
            appendLine("Message scope:")
            appendLine(messageScope)
            appendLine()
            appendLine("Last checked:")
            appendLine(formatLastChecked())
        }

        refreshStatusBtn.isEnabled = serverHealthStatus != ServerHealthStatus.CHECKING
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

    private fun startServer() {
        AppLogStore.append(this, "MainActivity", "Start server requested")
        ServerForegroundService.start(this)
        serverHealthStatus = ServerHealthStatus.CHECKING
        serverHealthDetail = "Waiting for the foreground service to answer /health"
        renderStatus()
        scheduleHealthRefresh(showToast = true)
    }

    private fun stopServer() {
        AppLogStore.append(this, "MainActivity", "Stop server requested")
        ServerForegroundService.stop(this)
        serverHealthStatus = ServerHealthStatus.CHECKING
        serverHealthDetail = "Waiting for the foreground service to stop"
        renderStatus()
        scheduleHealthRefresh(showToast = true)
    }

    private fun scheduleHealthRefresh(showToast: Boolean) {
        statusRefreshHandler.removeCallbacksAndMessages(null)
        statusRefreshHandler.postDelayed({
            checkServerHealth(showToast)
        }, 750)
    }

    private fun checkServerHealth(showToast: Boolean) {
        val healthUrl = ServerForegroundService.healthUrl()

        serverHealthStatus = ServerHealthStatus.CHECKING
        serverHealthDetail = "Calling $healthUrl"
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
                        val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                        val payload = JSONObject(responseBody)
                        HealthCheckResult(
                            isHealthy = true,
                            detail = "HTTP $responseCode ${connection.responseMessage}",
                            payload = payload,
                        )
                    } else {
                        HealthCheckResult(
                            isHealthy = false,
                            detail = "HTTP $responseCode ${connection.responseMessage}",
                            payload = null,
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
                    payload = null,
                )
            }

            runOnUiThread {
                serverHealthStatus = if (result.isHealthy) {
                    ServerHealthStatus.HEALTHY
                } else {
                    ServerHealthStatus.UNHEALTHY
                }
                serverHealthDetail = result.detail
                lastHealthCheckAt = System.currentTimeMillis()
                serverStartedAt = result.payload?.optString("startedAt")?.takeUnless { it.isNullOrBlank() }
                serverUptimeMs = result.payload?.takeIf { it.has("uptimeMs") }?.optLong("uptimeMs")
                smsPermissionGranted = result.payload?.optBoolean("smsPermissionGranted")
                    ?: DeviceSmsStore.hasReadPermission(this)
                appVersion = result.payload?.optString("appVersion")?.takeUnless { it.isNullOrBlank() }
                    ?: AppMetadata.versionName(this)
                messageScope = result.payload?.optString("messageScope")?.takeUnless { it.isNullOrBlank() }
                    ?: "all_device_sms"

                renderStatus()

                if (showToast) {
                    val message = if (result.isHealthy) {
                        "Phone server is reachable"
                    } else {
                        "Phone server is not reachable"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
            executor.shutdown()
        }
    }

    private fun formatLastChecked(): String {
        val timestamp = lastHealthCheckAt ?: return "Not checked yet"
        return DateFormat.getDateTimeInstance().format(Date(timestamp))
    }

    private fun formatUptime(uptimeMs: Long?): String {
        val value = uptimeMs ?: return "Unknown"
        val totalSeconds = value / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02dh %02dm %02ds", hours, minutes, seconds)
    }

    private fun refreshLocalNetworkDetails() {
        val networkInterface = networkInterfaces()
            .firstOrNull { it.addresses.any(Inet4Address::isSiteLocalAddress) }
            ?: networkInterfaces().firstOrNull()

        localNetworkAddress = networkInterface?.addresses?.firstOrNull { it.isSiteLocalAddress }?.hostAddress
            ?: networkInterface?.addresses?.firstOrNull()?.hostAddress
        localNetworkMacAddress = networkInterface?.hardwareAddress
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(":") { byte -> "%02X".format(Locale.US, byte.toInt() and 0xFF) }
    }

    private fun networkInterfaces(): List<NetworkInterfaceWithAddresses> {
        val interfaces = mutableListOf<NetworkInterface>()
        val networkInterfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (networkInterfaces.hasMoreElements()) {
            interfaces += networkInterfaces.nextElement()
        }

        return interfaces
            .asSequence()
            .filterNot { it.isLoopback || !it.isUp }
            .mapNotNull { networkInterface ->
                val addresses = mutableListOf<Inet4Address>()
                val inetAddresses: Enumeration<java.net.InetAddress> = networkInterface.inetAddresses
                while (inetAddresses.hasMoreElements()) {
                    val address = inetAddresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        addresses += address
                    }
                }

                networkInterface.takeIf { addresses.isNotEmpty() }?.let {
                    NetworkInterfaceWithAddresses(it, addresses)
                }
            }
            .toList()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private enum class ServerHealthStatus {
        CHECKING,
        HEALTHY,
        UNHEALTHY,
    }

    private data class HealthCheckResult(
        val isHealthy: Boolean,
        val detail: String,
        val payload: JSONObject?,
    )

    private data class NetworkInterfaceWithAddresses(
        val networkInterface: NetworkInterface,
        val addresses: List<Inet4Address>,
    ) {
        val hardwareAddress: ByteArray?
            get() = networkInterface.hardwareAddress
    }
}
