import fs from "fs/promises";
import path from "path";

const LOG_PATH =
  process.env.CLIENT_LOG_FILE?.trim() ||
  path.join(process.cwd(), "logs", "client.log");

export async function appendClientLogLine(
  entry: Record<string, unknown>,
  logWarn: (e: unknown) => void
): Promise<void> {
  const line = JSON.stringify({ ts: new Date().toISOString(), ...entry }) + "\n";
  try {
    await fs.mkdir(path.dirname(LOG_PATH), { recursive: true });
    await fs.appendFile(LOG_PATH, line, "utf8");
  } catch (err) {
    logWarn(err);
  }
}
