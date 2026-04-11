package com.example.smsapi

import android.content.Context
import fi.iki.elonen.NanoHTTPD
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
            .put("appVersion", AppMetadata.versionName(context))

        return jsonResponse(Response.Status.OK, payload)
    }

    private fun messagesResponse(session: IHTTPSession): Response {
        if (!DeviceSmsStore.hasReadPermission(context)) {
            return jsonResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                JSONObject().put("error", "READ_SMS permission is required"),
            )
        }

        val last = ApiContract.parseLastQuery(session.parameters["last"]?.firstOrNull())
            ?: return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject()
                    .put("error", "Invalid query parameter")
                    .put("details", JSONObject().put("last", "last must be a positive integer or -1")),
            )

        val messages = DeviceSmsStore.listMessages(context, last)
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

    private fun jsonResponse(status: Response.Status, payload: JSONObject): Response {
        val response = newFixedLengthResponse(status, "application/json; charset=utf-8", payload.toString())
        response.addHeader("Cache-Control", "no-store")
        return response
    }
}
