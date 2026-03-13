# SMS API Monorepo

Android app + Bun/Hono backend for forwarding incoming SMS messages to a Neon-hosted PostgreSQL database.

## Structure

- `android/` - Android app that receives SMS and forwards it to the backend
- `backend/` - Bun + Hono API backed by PostgreSQL

## Environment

Create a root `.env` file:

```env
DATABASE_URL=postgresql://username:password@host/database?sslmode=verify-full
PORT=6730
GROQ_API_KEY=your-groq-api-key
LOG_LEVEL=info
```

## Deploy

Run from the repo root:

```bash
docker compose up -d
```

The backend will be available on `http://<host>:6730` by default.

## Android App

Open `android/` in Android Studio. In the app, save your backend base URL, for example:

```text
http://192.168.1.128:6730
```

The app validates `GET /health` and then forwards incoming SMS messages to `POST /api/messages`.

## API

### `GET /health`

Returns service health.

Example response:

```json
{ "ok": true }
```

### `POST /api/messages`

Stores a received SMS message.

Request body:

```json
{
  "address": "+441234567890",
  "body": "Your code is 123456",
  "receivedAt": "2026-03-13T12:00:00.000Z"
}
```

### `GET /api/messages`

Returns stored messages ordered newest first.

Query parameters:

- `last` omitted - return the latest message
- `last=1` - return the latest message
- `last=<positive integer>` - return that many latest messages
- `last=-1` - return all messages

Example:

```text
GET /api/messages?last=5
```

### `GET /api/messages/code`

Uses Groq with `moonshotai/kimi-k2-instruct-0905` to extract the code from the latest stored message.

If Groq is unavailable or not configured, the backend falls back to built-in regex extraction.

Returns the extracted code as JSON.

Example response:

```json
{ "code": "123456" }
```

## Notes

- The backend creates the `messages` table automatically on startup
- Neon connections should use `sslmode=verify-full`
- Docker Compose automatically reads the root `.env` file
