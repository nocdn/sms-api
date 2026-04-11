# SMS API

This project is now a single Android app. The phone itself runs an HTTP server and serves SMS messages directly from the device, so there is no separate backend, database, or Docker deployment anymore.

## Structure

- `android/` - Android app with a foreground service that hosts the HTTP API on the phone

## How It Works

- The app starts a foreground service that runs an HTTP server on port `6770`
- The service survives app dismissal and is started again after reboot
- `GET /api/messages` reads SMS directly from the Android SMS content provider
- SMS stay on the phone; nothing is synced to an external backend

## Android App

Open `android/` in Android Studio or build with Gradle.

When the app launches it:

- requests `READ_SMS` permission if needed
- starts the foreground server service
- shows local server status and logs

The app serves HTTP on:

```text
http://127.0.0.1:6770
```

Use your own port-forwarding setup to expose that port to other devices.

## API

### `GET /health`

Returns server health and runtime metadata.

Example response:

```json
{
  "ok": true,
  "service": "smsapi-phone",
  "port": 6770,
  "startedAt": "2026-04-11T11:00:00.000Z",
  "uptimeMs": 123456,
  "smsPermissionGranted": true,
  "messageScope": "all_device_sms",
  "appVersion": "0.0.1"
}
```

### `GET /api/messages`

Returns SMS messages ordered newest first.

Query parameters:

- `last` omitted - return the latest message
- `last=1` - return the latest message
- `last=<positive integer>` - return that many latest messages
- `last=-1` - return all SMS messages available through the device SMS provider

Example requests:

```text
GET /api/messages
GET /api/messages?last=5
GET /api/messages?last=-1
```

Example response:

```json
{
  "data": [
    {
      "address": "+441234567890",
      "body": "Your code is 123456",
      "receivedAt": "2026-03-13T12:00:00.000Z"
    }
  ],
  "count": 1
}
```

If `READ_SMS` permission is missing, `GET /api/messages` returns `503`.

If `last` is invalid, `GET /api/messages` returns `400`.

## Notes

- The app does not add CORS headers
- The phone is the only source of truth for messages
- The foreground notification is required so Android keeps the server alive in the background
