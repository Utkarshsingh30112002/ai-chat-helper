import fs from "fs/promises";
import path from "path";

const LOG_PATH =
  process.env.SUGGEST_LOG_FILE?.trim() ||
  path.join(process.cwd(), "logs", "suggestions.log");

const INCLUDE_MESSAGE = process.env.SUGGEST_LOG_INCLUDE_MESSAGE !== "false";
const MESSAGE_MAX = Math.min(
  16000,
  Math.max(0, Number(process.env.SUGGEST_LOG_MESSAGE_MAX_CHARS ?? 2000) || 2000)
);

let ensuredDir = false;

export type ReceivedSnapshot = {
  /** Character length of incoming message (after trim) */
  messageLen: number;
  /** Truncated copy for logs (full up to MESSAGE_MAX when INCLUDE_MESSAGE) */
  message?: string;
  sender?: string;
  locale: string;
  tone: string;
  /** Client-selected reply mode (system prompt variant) */
  mode?: string;
};

export type SentSnapshot = {
  suggestions: string[];
};

export type AgentSuccessMetrics = {
  provider: "openai" | "gemini";
  model: string;
  /** OpenAI-compatible API base (OpenAI path only) */
  openaiBaseUrl?: string;
  latencyMs: number;
  temperature: number;
  maxTokens: number;
  usage?: {
    prompt_tokens?: number;
    completion_tokens?: number;
    total_tokens?: number;
  };
  finishReason?: string | null;
};

export type FailureKind =
  | "empty_model"
  | "json_parse"
  | "bad_shape"
  | "empty_strings"
  | "upstream"
  | "bad_request";

async function appendLine(
  lineObj: Record<string, unknown>,
  logWarn: (obj: { err: unknown }, msg: string) => void
): Promise<void> {
  const line = JSON.stringify(lineObj) + "\n";
  try {
    if (!ensuredDir) {
      await fs.mkdir(path.dirname(LOG_PATH), { recursive: true });
      ensuredDir = true;
    }
    await fs.appendFile(LOG_PATH, line, "utf8");
  } catch (err) {
    logWarn({ err }, "suggestion file log write failed");
  }
}

function buildReceived(r: ReceivedSnapshot): Record<string, unknown> {
  const out: Record<string, unknown> = {
    messageLen: r.messageLen,
    locale: r.locale,
    tone: r.tone,
  };
  if (r.mode !== undefined) {
    out.mode = r.mode;
  }
  if (r.sender) {
    out.sender = r.sender;
  }
  if (INCLUDE_MESSAGE && r.message !== undefined) {
    out.message = r.message.slice(0, MESSAGE_MAX);
  }
  return out;
}

/**
 * One JSON line: successful agent run (input, output, latency, tokens).
 */
export async function appendAgentSuccessLog(
  input: {
    received: ReceivedSnapshot;
    sent: SentSnapshot;
    agent: AgentSuccessMetrics;
  },
  logWarn: (obj: { err: unknown }, msg: string) => void
): Promise<void> {
  await appendLine(
    {
      ts: new Date().toISOString(),
      event: "suggest_success",
      received: buildReceived(input.received),
      sent: { suggestions: input.sent.suggestions },
      agent: {
        provider: input.agent.provider,
        model: input.agent.model,
        ...(input.agent.openaiBaseUrl != null && input.agent.openaiBaseUrl !== ""
          ? { openaiBaseUrl: input.agent.openaiBaseUrl }
          : {}),
        latencyMs: input.agent.latencyMs,
        temperature: input.agent.temperature,
        maxTokens: input.agent.maxTokens,
        usage: input.agent.usage ?? null,
        finishReason: input.agent.finishReason ?? null,
      },
    },
    logWarn
  );
}

/**
 * One JSON line: failed agent run (input + failure reason). Helps debug bad outputs / API issues.
 */
export async function appendAgentFailureLog(
  input: {
    received: ReceivedSnapshot;
    kind: FailureKind;
    detail?: string;
    /** First N chars of raw model text when parse/shape fails */
    rawModelPreview?: string;
    agent?: Partial<AgentSuccessMetrics> & { latencyMs?: number };
  },
  logWarn: (obj: { err: unknown }, msg: string) => void
): Promise<void> {
  const line: Record<string, unknown> = {
    ts: new Date().toISOString(),
    event: "suggest_failure",
    kind: input.kind,
    received: buildReceived(input.received),
  };
  if (input.detail) {
    line.detail = input.detail.slice(0, 2000);
  }
  if (input.rawModelPreview) {
    line.rawModelPreview = input.rawModelPreview.slice(0, 4000);
  }
  if (input.agent && Object.keys(input.agent).length > 0) {
    line.agent = input.agent;
  }
  await appendLine(line, logWarn);
}
