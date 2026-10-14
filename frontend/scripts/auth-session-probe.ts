import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { authenticatedState } from "../src/lib/auth/session";
import { closeDatabase } from "../src/lib/auth/store";

try {
  const input: unknown = JSON.parse(readFileSync(0, "utf8"));
  if (!input || typeof input !== "object" || !("environment" in input) || !input.environment ||
      typeof input.environment !== "object" || !("session" in input) || typeof input.session !== "string") {
    throw new TypeError("Invalid isolated probe input");
  }
  for (const [key, value] of Object.entries(input.environment)) {
    if (typeof value !== "string") throw new TypeError("Invalid isolated probe environment");
    process.env[key] = value;
  }
  if (process.env.STOREFRONT_ORIGIN !== "http://127.0.0.1:3300") throw new TypeError("Use the isolated stack");
  const result = await authenticatedState(input.session);
  if (!result.state.tokens) throw new TypeError("Missing authenticated grant");
  console.info(JSON.stringify({
    id: result.user.id, fingerprint: createHash("sha256").update(result.state.tokens.accessToken).digest("hex"),
  }));
} catch {
  console.error("Isolated session probe failed; credentials and provider responses suppressed.");
  process.exitCode = 1;
} finally {
  await closeDatabase();
}
