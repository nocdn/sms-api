package com.example.smsapi

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

class PhoneHttpServer(
    private val context: Context,
    private val startedAtMillis: Long,
) : NanoHTTPD(ApiContract.SERVER_PORT) {

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.orEmpty()
        val query = session.queryParameterString?.takeUnless { it.isBlank() }?.let { "?$it" }.orEmpty()
        val remoteAddress = session.remoteIpAddress ?: "unknown"

        AppLogStore.append(
            context,
            "Http",
            "IN  ${session.method} $path$query from $remoteAddress",
        )

        return try {
            when {
                path == ApiContract.HEALTH_PATH && session.method == Method.GET -> healthResponse()
                path == ApiContract.MESSAGES_PATH && session.method == Method.GET -> messagesResponse(session)
                path == ApiContract.HEALTH_PATH || path == ApiContract.MESSAGES_PATH -> jsonResponse(
                    Response.Status.METHOD_NOT_ALLOWED,
                    JSONObject().put("error", "Method not allowed"),
                )

                else -> jsonResponse(
                    Response.Status.NOT_FOUND,
                    JSONObject().put("error", "Not found"),
                )
            }
        } catch (error: Exception) {
            AppLogStore.append(
                context,
                "Http",
                "ERR ${session.method} $path$query <- ${error.message ?: error::class.java.simpleName}",
            )
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("error", "Internal server error"),
            )
        }
    }

    private fun healthResponse(): Response {
        val payload = JSONObject()
            .put("ok", true)
            .put("service", "smsapi-phone")
            .put("port", ApiContract.SERVER_PORT)
            .put("startedAt", ApiContract.formatTimestamp(startedAtMillis))
            .put("uptimeMs", System.currentTimeMillis() - startedAtMillis)
            .put("smsPermissionGranted", DeviceSmsStore.hasReadPermission(context))
            .put("messageScope", "all_device_sms")
            .put("messagesRequireApiKey", true)
            .put("apiKeyCount", ApiKeyStore.listKeys(context).size)
            .put("appVersion", AppMetadata.versionName(context))

        return jsonResponse(Response.Status.OK, payload)
    }

    private fun messagesResponse(session: IHTTPSession): Response {
        if (session.parameters.containsKey("last")) {
            AppLogStore.append(context, "Http", "WARN rejected query parameter 'last' for ${session.method} ${session.uri}")
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject()
                    .put("error", "Invalid request")
                    .put("details", JSONObject().put("last", "Send 'last' in the JSON request body instead of the query string")),
            )
        }

        val apiKeyValue = bearerToken(session)
            ?: return unauthorizedResponse(session, "Authorization header must be Bearer <api key>")
        if (!ApiKeyStore.containsValue(context, apiKeyValue)) {
            return unauthorizedResponse(session, "Invalid API key")
        }

        if (!DeviceSmsStore.hasReadPermission(context)) {
            return jsonResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                JSONObject().put("error", "READ_SMS permission is required"),
            )
        }

        val rawRequestBody = readRequestBody(session)
        AppLogStore.append(
            context,
            "Http",
            "Messages request body: ${rawRequestBody ?: "<empty>"}",
        )

        val request = ApiContract.parseMessagesRequest(rawRequestBody)
            ?: return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject()
                    .put("error", "Invalid request body")
                    .put("details", JSONObject().put("last", "last must be a positive integer or -1")),
            )

        val messages = DeviceSmsStore.listMessages(context, request.last)
        val payload = JSONObject()
            .put(
                "data",
                JSONArray().apply {
                    messages.forEach { message ->
                        put(
                            JSONObject()
                                .put("address", message.address)
                                .put("body", message.body)
                                .put("receivedAt", ApiContract.formatTimestamp(message.receivedAt)),
                        )
                    }
                },
            )
            .put("count", messages.size)

        return jsonResponse(Response.Status.OK, payload)
    }

    private fun bearerToken(session: IHTTPSession): String? {
        val authorizationHeader = session.headers.entries
            .firstOrNull { it.key.equals("authorization", ignoreCase = true) }
            ?.value
            ?.trim()
            ?: return null

        if (!authorizationHeader.startsWith("Bearer ", ignoreCase = true)) {
            return null
        }

        return authorizationHeader.substringAfter(' ').trim().takeIf { it.isNotEmpty() }
    }

    private fun readRequestBody(session: IHTTPSession): String? {
        val contentLength = session.headers.entries
            .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
            ?.value
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: return null

        val output = ByteArrayOutputStream(contentLength)
        val buffer = ByteArray(minOf(contentLength, 1024))
        var remaining = contentLength
        while (remaining > 0) {
            val bytesRead = session.inputStream.read(buffer, 0, minOf(buffer.size, remaining))
            if (bytesRead <= 0) {
                break
            }
            output.write(buffer, 0, bytesRead)
            remaining -= bytesRead
        }

        return output.toString(Charsets.UTF_8.name()).trim().takeUnless { it.isEmpty() }
    }

    private fun unauthorizedResponse(session: IHTTPSession, message: String): Response {
        AppLogStore.append(
            context,
            "Http",
            "WARN unauthorized ${session.method} ${session.uri}: $message",
        )
        return jsonResponse(
            Response.Status.UNAUTHORIZED,
            JSONObject().put("error", message),
        ).apply {
            addHeader("WWW-Authenticate", "Bearer")
        }
    }

    private fun jsonResponse(status: Response.Status, payload: JSONObject): Response {
        val response = newFixedLengthResponse(status, "application/json; charset=utf-8", payload.toString())
        response.addHeader("Cache-Control", "no-store")
        return response
    }
}
