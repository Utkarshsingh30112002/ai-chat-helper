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
| `RATE_LIMIT_MAX` | Max requests per IP per window for `/v1/suggest` (default `30`) |
| `RATE_LIMIT_TIME_WINDOW` | Window, e.g. `1 minute` or `60000` ms (default `1 minute`) |
| `SUGGEST_LOG_FILE` | Path for JSON-lines agent log (default `./logs/suggestions.log`) |
| `SUGGEST_LOG_INCLUDE_MESSAGE` | Set `false` to omit `received.message` (length/locale/tone still logged) |
| `SUGGEST_LOG_MESSAGE_MAX_CHARS` | Max chars of `received.message` when included (default `2000`, cap `16000`) |

`/health` is **not** counted toward the rate limit so uptime checks do not burn the quota.

**Agent log file** (gitignored `logs/`): each line is one JSON object.

**Client log file** (`logs/client.log` by default, override with `CLIENT_LOG_FILE`): Android app POSTs to `POST /v1/client-log` (same bearer as `/v1/suggest`). Rate limit does **not** apply to this path. Body: `{ "level", "message", "stack?", "appVersion?", "androidSdk?", "deviceModel?" }`.

- **`suggest_success`**: `received` (message length, optional truncated `message`, `sender`, `locale`, `tone`), `sent.suggestions`, and `agent` (`model`, `openaiBaseUrl`, `latencyMs`, `temperature`, `maxTokens`, token `usage`, `finishReason`).
- **`suggest_failure`**: same `received`, plus `kind` (`empty_model`, `json_parse`, `bad_shape`, `empty_strings`, `upstream`), `detail`, optional `rawModelPreview` (bad JSON/shape), optional `agent` metrics when the API returned a body.

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

- `GET /health` — `{ "ok": true, "stats": { ... } }` — includes uptime, total requests, suggest success/errors, and how many were **rate limited**
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
