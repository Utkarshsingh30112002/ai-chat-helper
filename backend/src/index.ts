import "dotenv/config";
import Fastify from "fastify";
import rateLimit from "@fastify/rate-limit";
import OpenAI from "openai";

const PORT = Number(process.env.PORT ?? 3000);
const APP_SECRET = process.env.APP_SECRET ?? "";
const MODEL = process.env.MODEL ?? "gpt-4o-mini";
const OPENAI_API_KEY = process.env.OPENAI_API_KEY ?? "";
const OPENAI_BASE_URL = process.env.OPENAI_BASE_URL ?? "https://api.openai.com/v1";

const app = Fastify({
  logger: true,
  bodyLimit: 64 * 1024,
});

await app.register(rateLimit, {
  max: 60,
  timeWindow: "1 minute",
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

app.get("/health", async () => ({ ok: true }));

type SuggestBody = {
  message: string;
  sender?: string;
  locale?: string;
  tone?: "neutral" | "friendly" | "brief";
};

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

  const completion = await openai.chat.completions.create({
    model: MODEL,
    messages: [
      { role: "system", content: system },
      { role: "user", content: userParts.join("\n\n") },
    ],
    response_format: { type: "json_object" },
    temperature: 0.7,
    max_tokens: 500,
  });

  const raw = completion.choices[0]?.message?.content;
  if (!raw) {
    return reply.status(502).send({ error: "Empty model response" });
  }

  let parsed: { suggestions?: unknown };
  try {
    parsed = JSON.parse(raw) as { suggestions?: unknown };
  } catch {
    return reply.status(502).send({ error: "Invalid JSON from model" });
  }

  const suggestions = parsed.suggestions;
  if (!Array.isArray(suggestions) || suggestions.length !== 3) {
    return reply.status(502).send({ error: "Model did not return exactly 3 suggestions" });
  }

  const strings = suggestions.map((s) => (typeof s === "string" ? s.trim() : ""));
  if (strings.some((s) => !s)) {
    return reply.status(502).send({ error: "Invalid suggestion strings" });
  }

  return { suggestions: strings };
});

try {
  await app.listen({ port: PORT, host: "0.0.0.0" });
} catch (err) {
  app.log.error(err);
  process.exit(1);
}
