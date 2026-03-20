import "dotenv/config";
import Fastify, { type FastifyRequest } from "fastify";
import rateLimit from "@fastify/rate-limit";
import OpenAI from "openai";
import { appendClientLogLine } from "./clientLogFile.js";
import {
  appendAgentFailureLog,
  appendAgentSuccessLog,
  type ReceivedSnapshot,
} from "./suggestionFileLog.js";

const PORT = Number(process.env.PORT ?? 3000);
const APP_SECRET = process.env.APP_SECRET ?? "";
const MODEL = process.env.MODEL ?? "gpt-4o-mini";
const OPENAI_API_KEY = process.env.OPENAI_API_KEY ?? "";
const OPENAI_BASE_URL = process.env.OPENAI_BASE_URL ?? "https://api.openai.com/v1";

const CHAT_TEMPERATURE = 0.7;
const CHAT_MAX_TOKENS = 500;

const RATE_LIMIT_MAX = Math.max(1, Number(process.env.RATE_LIMIT_MAX ?? 30));
const RATE_LIMIT_WINDOW = process.env.RATE_LIMIT_TIME_WINDOW ?? "1 minute";

const stats = {
  startedAt: Date.now(),
  requestsTotal: 0,
  healthHits: 0,
  suggestAttempts: 0,
  suggestSuccess: 0,
  suggestOpenAiErrors: 0,
  rateLimited: 0,
  clientLogIngest: 0,
};

const app = Fastify({
  logger: true,
  bodyLimit: 64 * 1024,
});

app.addHook("onRequest", async (request) => {
  stats.requestsTotal++;
  const path = request.url.split("?")[0] ?? request.url;
  if (path === "/health") {
    stats.healthHits++;
  }
  if (path === "/v1/suggest" && request.method === "POST") {
    stats.suggestAttempts++;
  }
});

await app.register(rateLimit, {
  max: RATE_LIMIT_MAX,
  timeWindow: RATE_LIMIT_WINDOW,
  allowList: (request: FastifyRequest) => {
    const path = request.url.split("?")[0] ?? request.url;
    return path === "/health" || path === "/v1/client-log";
  },
  onExceeded: (request: FastifyRequest) => {
    stats.rateLimited++;
    app.log.warn(
      {
        ip: request.ip,
        rateLimitedTotal: stats.rateLimited,
        max: RATE_LIMIT_MAX,
        window: RATE_LIMIT_WINDOW,
      },
      "rate limit exceeded (request rejected)"
    );
  },
});

function requireAuth(
  authHeader: string | undefined
): { ok: true } | { ok: false; status: number; message: string } {
  if (!APP_SECRET) {
    return { ok: false, status: 500, message: "Server misconfigured: APP_SECRET not set" };
  }
  const prefix = "Bearer ";
  if (!authHeader?.startsWith(prefix)) {
    return { ok: false, status: 401, message: "Missing or invalid Authorization header" };
  }
  const token = authHeader.slice(prefix.length).trim();
  if (token !== APP_SECRET) {
    return { ok: false, status: 401, message: "Invalid token" };
  }
  return { ok: true };
}

app.get("/health", async () => ({
  ok: true,
  stats: {
    uptimeSeconds: Math.floor((Date.now() - stats.startedAt) / 1000),
    requestsTotal: stats.requestsTotal,
    healthHits: stats.healthHits,
    suggestAttempts: stats.suggestAttempts,
    suggestSuccess: stats.suggestSuccess,
    suggestOpenAiErrors: stats.suggestOpenAiErrors,
    rateLimited: stats.rateLimited,
    clientLogIngest: stats.clientLogIngest,
    rateLimit: { maxPerIp: RATE_LIMIT_MAX, window: RATE_LIMIT_WINDOW },
  },
}));

type ClientLogBody = {
  level?: string;
  message: string;
  stack?: string;
  appVersion?: string;
  androidSdk?: number;
  deviceModel?: string;
};

app.post<{ Body: ClientLogBody }>("/v1/client-log", async (request, reply) => {
  const auth = requireAuth(request.headers.authorization);
  if (!auth.ok) {
    return reply.status(auth.status).send({ error: auth.message });
  }

  const body = request.body;
  if (!body || typeof body.message !== "string" || body.message.trim().length === 0) {
    return reply.status(400).send({ error: "Field \"message\" is required" });
  }

  const level = typeof body.level === "string" && body.level.length > 0 ? body.level.slice(0, 32) : "info";
  const message = body.message.trim().slice(0, 8000);
  const stack = typeof body.stack === "string" ? body.stack.slice(0, 16000) : undefined;
  const appVersion = typeof body.appVersion === "string" ? body.appVersion.slice(0, 64) : undefined;
  const androidSdk = typeof body.androidSdk === "number" ? body.androidSdk : undefined;
  const deviceModel = typeof body.deviceModel === "string" ? body.deviceModel.slice(0, 128) : undefined;

  stats.clientLogIngest++;
  await appendClientLogLine(
    {
      level,
      message,
      stack,
      appVersion,
      androidSdk,
      deviceModel,
      ip: request.ip,
    },
    (e) => app.log.warn(e)
  );

  app.log.info({ level, clientLogTotal: stats.clientLogIngest }, "client log ingested");
  return { ok: true };
});

type SuggestBody = {
  message: string;
  sender?: string;
  locale?: string;
  tone?: "neutral" | "friendly" | "brief";
};

function receivedSnapshot(
  message: string,
  sender: string | undefined,
  locale: string,
  tone: string
): ReceivedSnapshot {
  return {
    messageLen: message.length,
    message,
    sender,
    locale,
    tone,
  };
}

app.post<{ Body: SuggestBody }>("/v1/suggest", async (request, reply) => {
  const auth = requireAuth(request.headers.authorization);
  if (!auth.ok) {
    return reply.status(auth.status).send({ error: auth.message });
  }

  if (!OPENAI_API_KEY) {
    return reply.status(500).send({ error: "Server misconfigured: OPENAI_API_KEY not set" });
  }

  const body = request.body;
  if (!body || typeof body.message !== "string" || body.message.trim().length === 0) {
    return reply.status(400).send({ error: "Field \"message\" is required and must be a non-empty string" });
  }

  const message = body.message.trim().slice(0, 16000);
  const sender = body.sender?.trim();
  const locale = body.locale?.trim() || "en";
  const tone = body.tone ?? "neutral";

  const received = receivedSnapshot(message, sender, locale, tone);

  const openai = new OpenAI({
    apiKey: OPENAI_API_KEY,
    baseURL: OPENAI_BASE_URL,
  });

  const system = `You help draft short reply options for a chat app. Return ONLY valid JSON with this exact shape: {"suggestions":["...","...","..."]}
Rules:
- Exactly 3 strings in "suggestions".
- Each reply is concise and natural for the conversation.
- Tone: ${tone}.
- Locale / language for replies: ${locale}.
- No harassment, threats, or illegal content. If the message is abusive, suggest calm, boundary-setting replies.
- Do not include markdown or explanations outside the JSON.`;

  const userParts = [
    sender ? `Sender/handle (if known): ${sender}` : null,
    `Incoming message to respond to:\n${message}`,
  ].filter(Boolean);

  const baseAgent = {
    model: MODEL,
    openaiBaseUrl: OPENAI_BASE_URL,
    temperature: CHAT_TEMPERATURE,
    maxTokens: CHAT_MAX_TOKENS,
  };

  try {
    const t0 = Date.now();
    const completion = await openai.chat.completions.create({
      model: MODEL,
      messages: [
        { role: "system", content: system },
        { role: "user", content: userParts.join("\n\n") },
      ],
      response_format: { type: "json_object" },
      temperature: CHAT_TEMPERATURE,
      max_tokens: CHAT_MAX_TOKENS,
    });
    const latencyMs = Date.now() - t0;

    const usage = completion.usage
      ? {
          prompt_tokens: completion.usage.prompt_tokens,
          completion_tokens: completion.usage.completion_tokens,
          total_tokens: completion.usage.total_tokens,
        }
      : undefined;
    const finishReason = completion.choices[0]?.finish_reason ?? null;

    const raw = completion.choices[0]?.message?.content;
    if (!raw) {
      stats.suggestOpenAiErrors++;
      await appendAgentFailureLog(
        {
          received,
          kind: "empty_model",
          detail: "choices[0].message.content was empty",
          agent: { ...baseAgent, latencyMs, usage, finishReason },
        },
        (obj, msg) => app.log.warn(obj, msg)
      );
      return reply.status(502).send({ error: "Empty model response" });
    }

    let parsed: { suggestions?: unknown };
    try {
      parsed = JSON.parse(raw) as { suggestions?: unknown };
    } catch (e) {
      stats.suggestOpenAiErrors++;
      await appendAgentFailureLog(
        {
          received,
          kind: "json_parse",
          detail: e instanceof Error ? e.message : String(e),
          rawModelPreview: raw,
          agent: { ...baseAgent, latencyMs, usage, finishReason },
        },
        (obj, msg) => app.log.warn(obj, msg)
      );
      return reply.status(502).send({ error: "Invalid JSON from model" });
    }

    const suggestions = parsed.suggestions;
    if (!Array.isArray(suggestions) || suggestions.length !== 3) {
      stats.suggestOpenAiErrors++;
      await appendAgentFailureLog(
        {
          received,
          kind: "bad_shape",
          detail: `suggestions must be array of length 3, got ${Array.isArray(suggestions) ? `length ${suggestions.length}` : typeof suggestions}`,
          rawModelPreview: raw,
          agent: { ...baseAgent, latencyMs, usage, finishReason },
        },
        (obj, msg) => app.log.warn(obj, msg)
      );
      return reply.status(502).send({ error: "Model did not return exactly 3 suggestions" });
    }

    const strings = suggestions.map((s) => (typeof s === "string" ? s.trim() : ""));
    if (strings.some((s) => !s)) {
      stats.suggestOpenAiErrors++;
      await appendAgentFailureLog(
        {
          received,
          kind: "empty_strings",
          detail: "one or more suggestion strings empty after trim",
          rawModelPreview: raw,
          agent: { ...baseAgent, latencyMs, usage, finishReason },
        },
        (obj, msg) => app.log.warn(obj, msg)
      );
      return reply.status(502).send({ error: "Invalid suggestion strings" });
    }

    stats.suggestSuccess++;
    app.log.info(
      {
        suggestSuccessTotal: stats.suggestSuccess,
        suggestAttempts: stats.suggestAttempts,
        rateLimitedTotal: stats.rateLimited,
      },
      "POST /v1/suggest completed"
    );

    await appendAgentSuccessLog(
      {
        received,
        sent: { suggestions: strings },
        agent: {
          ...baseAgent,
          latencyMs,
          usage,
          finishReason,
        },
      },
      (obj, msg) => app.log.warn(obj, msg)
    );

    return { suggestions: strings };
  } catch (err) {
    stats.suggestOpenAiErrors++;
    app.log.error({ err }, "POST /v1/suggest OpenAI error");
    await appendAgentFailureLog(
      {
        received,
        kind: "upstream",
        detail: err instanceof Error ? err.message : String(err),
      },
      (obj, msg) => app.log.warn(obj, msg)
    );
    return reply.status(502).send({ error: "Upstream model request failed" });
  }
});

try {
  await app.listen({ port: PORT, host: "0.0.0.0" });
  app.log.info(
    { RATE_LIMIT_MAX, RATE_LIMIT_WINDOW, healthSkipsRateLimit: true, clientLogUnlimited: true },
    "rate limit: /v1/suggest per IP; /health and /v1/client-log excluded"
  );
} catch (err) {
  app.log.error(err);
  process.exit(1);
}
