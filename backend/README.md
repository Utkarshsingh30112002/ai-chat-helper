# WhatsApp Reply Suggestion API

Node.js (Fastify) backend that calls an OpenAI-compatible API to return three short reply suggestions.

## Environment

Copy `.env.example` to `.env` and set:

| Variable | Description |
|----------|-------------|
| `PORT` | Listen port (default `3000`) |
| `APP_SECRET` | Shared secret; clients send `Authorization: Bearer <APP_SECRET>` |
| `OPENAI_API_KEY` | API key for your provider |
| `OPENAI_BASE_URL` | Base URL (default OpenAI: `https://api.openai.com/v1`) |
| `MODEL` | Model id (e.g. `gpt-4o-mini`) |

## Run locally

```bash
npm install
npm run dev
```

## Build & production

```bash
npm install
npm run build
npm start
```

## API

- `GET /health` — `{ "ok": true }`
- `POST /v1/suggest` — requires `Authorization: Bearer <APP_SECRET>`

Request body:

```json
{
  "message": "text to reply to",
  "sender": "optional",
  "locale": "en",
  "tone": "neutral"
}
```

`tone` is one of: `neutral`, `friendly`, `brief`.

Response:

```json
{
  "suggestions": ["...", "...", "..."]
}
```

## Docker

```bash
docker build -t reply-suggest-api .
docker run --env-file .env -p 3000:3000 reply-suggest-api
```

## Deploy (Railway / Fly / Render)

1. Set the same environment variables in the platform dashboard.
2. Bind to `PORT` provided by the host (often injected automatically).
3. Use HTTPS at the edge; the Android app should call your public URL with the bearer token.
