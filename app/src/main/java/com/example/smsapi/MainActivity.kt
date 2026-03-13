package com.example.smsapi

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.io.OutputStreamWriter
import java.net.URL
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val SMS_PERMISSION_CODE = 101
    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var serverSocket: ServerSocket? = null
    private var localIpAddress = "127.0.0.1"
    @Volatile
    private var publicIpAddress = "Checking..."
    @Volatile
    private var serverStatus = "Server starting..."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        localIpAddress = getBestIpAddress()
        val port = 6730

        checkSmsPermission()
        renderStatus(statusText, port)

        fetchPublicIp(statusText, port)
        startHttpServer(port, statusText)
    }

    private fun checkSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), SMS_PERMISSION_CODE)
        }
    }

    private fun readSms(limit: Int): String {
        return try {
            val sortOrder = if (limit > 0) {
                "${Telephony.Sms.Inbox.DATE} DESC LIMIT $limit"
            } else {
                "${Telephony.Sms.Inbox.DATE} DESC"
            }

            val cursor = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.Inbox.ADDRESS, Telephony.Sms.Inbox.BODY, Telephony.Sms.Inbox.DATE),
                null,
                null,
                sortOrder
            )

            val messages = mutableListOf<String>()

            cursor?.use {
                while (it.moveToNext()) {
                    val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.ADDRESS)) ?: "Unknown"
                    val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY)) ?: ""
                    val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.DATE))

                    val escapedAddress = escapeJson(address)
                    val escapedBody = escapeJson(body)

                    messages.add("{\"address\":\"$escapedAddress\",\"body\":\"$escapedBody\",\"date\":$date}")
                }
            }

            if (messages.isEmpty()) {
                "{\"messages\":[],\"count\":0}"
            } else {
                "{\"messages\":[${messages.joinToString(", ")}],\"count\":${messages.size}}"
            }
        } catch (e: Exception) {
            val escapedError = escapeJson(e.message ?: "Unknown error")
            "{\"error\":\"$escapedError\"}"
        }
    }

    private fun escapeJson(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
    }

    private fun startHttpServer(port: Int, statusText: TextView) {
        serverExecutor.execute {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                serverStatus = "Server running"

                runOnUiThread {
                    renderStatus(statusText, port)
                }

                while (!socket.isClosed) {
                    val client = socket.accept()
                    client.soTimeout = 5_000

                    client.use { connectedClient ->
                        val reader = connectedClient.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()

                        while (reader.readLine()?.isNotEmpty() == true) {
                            // Consume headers.
                        }

                        val body = if (requestLine.startsWith("GET /")) {
                            val parts = requestLine.split(" ")
                            val path = parts.getOrNull(1) ?: "/"
                            val lastParam = if (path.contains("last=")) {
                                path.substringAfter("last=").substringBefore("&").toIntOrNull() ?: 1
                            } else {
                                1
                            }
                            readSms(lastParam)
                        } else {
                            "{\"error\":\"Not Found\"}"
                        }

                        val statusLine = if (requestLine.startsWith("GET /")) {
                            "HTTP/1.1 200 OK"
                        } else {
                            "HTTP/1.1 404 Not Found"
                        }

                        val writer = OutputStreamWriter(connectedClient.getOutputStream(), Charsets.UTF_8)
                        writer.write(statusLine + "\r\n")
                        writer.write("Content-Type: application/json; charset=utf-8\r\n")
                        writer.write("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
                        writer.write("Connection: close\r\n")
                        writer.write("\r\n")
                        writer.write(body)
                        writer.flush()
                    }
                }
            } catch (e: Exception) {
                serverStatus = "Server failed to start"
                runOnUiThread {
                    statusText.text = buildString {
                        appendLine(serverStatus)
                        appendLine()
                        appendLine(e.javaClass.simpleName + ": " + (e.message ?: "Unknown error"))
                        appendLine()
                        appendLine("Local endpoint:")
                        appendLine("http://$localIpAddress:$port/")
                        appendLine()
                        appendLine("Public IP:")
                        appendLine(publicIpAddress)
                    }
                }
            }
        }
    }

    private fun fetchPublicIp(statusText: TextView, port: Int) {
        networkExecutor.execute {
            publicIpAddress = try {
                val connection = (URL("https://api.ipify.org").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    doInput = true
                }

                try {
                    BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        reader.readLine()?.trim()?.takeUnless { it.isEmpty() } ?: "Unavailable"
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                "Unavailable"
            }

            runOnUiThread {
                renderStatus(statusText, port)
            }
        }
    }

    private fun renderStatus(statusText: TextView, port: Int) {
        statusText.text = buildString {
            appendLine(serverStatus)
            appendLine()
            appendLine("Local endpoint:")
            appendLine("http://$localIpAddress:$port/")
            appendLine()
            appendLine("Public IP:")
            appendLine(publicIpAddress)
            appendLine()
            appendLine("Try GET /?last=5")
            appendLine("Returns the last 5 messages")
            appendLine()
            appendLine("Try GET /?last=-1")
            appendLine("Returns all messages")
        }
    }

    private fun getBestIpAddress(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    val hostAddress = address.hostAddress
                    if (!address.isLoopbackAddress && hostAddress != null && !hostAddress.contains(':')) {
                        return hostAddress
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    override fun onDestroy() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverExecutor.shutdownNow()
        networkExecutor.shutdownNow()
        super.onDestroy()
    }
}
