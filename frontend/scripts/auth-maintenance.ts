import { maintainSessions } from "../src/lib/auth/session";
import { closeDatabase } from "../src/lib/auth/store";

try {
  const result = await maintainSessions();
  console.info(`Authentication maintenance: revoked=${result.revoked}, pending=${result.pending}`);
  if (result.pending) process.exitCode = 1;
} catch {
  console.error("Authentication maintenance failed; retry with the configured server environment.");
  process.exitCode = 1;
} finally {
  await closeDatabase();
}
