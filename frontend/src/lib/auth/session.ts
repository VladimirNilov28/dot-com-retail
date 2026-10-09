import "server-only";
import { cache } from "react";
import { headers } from "next/headers";
import { authConfig } from "./config";
import { AuthError, assertSameProvider, authCookie, grantTokens, hasSessionCookie, type BrowserState } from "./model";
import type { ProviderSession } from "./model";
import { currentProviderSession, bridgeRequest } from "./provider";
import { refreshGrant, revokeGrant } from "./oauth";
import { withBrowser, withBrowserHash, revokeBrowser, database, identifierHash } from "./store";
import { storefrontMe } from "@/lib/graphql/operations";
import { hiveConfig, requestHive } from "@/lib/graphql/server-transport";

export type AccountState =
  | { kind: "anonymous" }
  | { kind: "expired" }
  | { kind: "unavailable" }
  | { kind: "authenticated"; user: { id: string; username: string; email: string; role: string } };

export async function readCurrentUser(accessToken: string, provider: ProviderSession) {
  const response = await requestHive(storefrontMe, {}, hiveConfig(process.env), { kind: "authenticated", accessToken });
  if (response.result.status !== "success") {
    if (response.result.error.kind === "unauthenticated" || response.result.error.kind === "forbidden") {
      throw new AuthError("reauthentication_required", 401);
    }
    throw new AuthError("unavailable");
  }
  const user = response.result.data.me;
  if (user.id !== provider.userId || user.role !== provider.role) throw new AuthError("reauthentication_required", 401);
  return user;
}

export async function authenticatedState(id: string): Promise<{ state: BrowserState; user: {
  id: string; username: string; email: string; role: string;
} }> {
  let refreshAttempted = false;
  try {
    return await withBrowser(id, async (state, _client, row) => {
      if (!row.authenticated || !state.tokens || !state.provider) throw new AuthError("expired", 401);
      const provider = await currentProviderSession(state);
      assertSameProvider(state.provider, provider);
      if (state.tokens.expiresAt <= Date.now() + 30000) {
        refreshAttempted = true;
        const tokens = await refreshGrant(state.tokens.refreshToken);
        const claims = tokens.claims();
        if (claims && claims.sub !== provider.userId) throw new AuthError("reauthentication_required", 401);
        state.tokens = grantTokens(tokens);
      }
      const user = await readCurrentUser(state.tokens.accessToken, provider);
      state.provider = provider;
      return { state, user };
    });
  } catch (error) {
    const failure = refreshAttempted && !(error instanceof AuthError &&
      (error.code === "expired" || error.code === "reauthentication_required"))
      ? new AuthError("reauthentication_required", 401) : error;
    if (failure !== error) console.error("OAuth refresh could not be safely committed; reauthentication required");
    if (failure instanceof AuthError && (failure.code === "expired" || failure.code === "reauthentication_required")) {
      await revokeBrowser(id);
    }
    throw failure;
  }
}

export const accountState = cache(async (): Promise<AccountState> => {
  const request = await headers();
  if (!hasSessionCookie(request.get("cookie"))) return { kind: "anonymous" };
  try {
    const config = authConfig();
    const cookie = authCookie(request.get("cookie"), config.sessionCookie);
    if (!cookie) return { kind: "expired" };
    const result = await authenticatedState(cookie);
    return { kind: "authenticated", user: result.user };
  } catch (error) {
    if (error instanceof AuthError && (error.code === "expired" || error.code === "reauthentication_required")) {
      return { kind: "expired" };
    }
    console.error("account authentication unavailable", error instanceof AuthError ? error.code : "upstream");
    return { kind: "unavailable" };
  }
});

export async function revokeSession(id: string): Promise<void> {
  await revokeBrowser(id);
  await withBrowser(id, async (state, client) => {
    if (!state.provider || !state.tokens) throw new AuthError("unavailable");
    if (state.revokeProvider !== false) await bridgeRequest("logout", { session_id: state.provider.sessionId });
    await revokeGrant(state.tokens.refreshToken);
    await client.query("UPDATE browser_sessions SET revocation_pending=false WHERE id_hash=$1", [identifierHash(id)]);
  }, true);
}

export async function maintainSessions(): Promise<{ revoked: number; pending: number }> {
  await database().query(
    "WITH expired AS (SELECT id_hash FROM browser_sessions WHERE authenticated=true AND revoked_at IS NULL "
      + "AND (expires_at<=now() OR idle_until<=now()) ORDER BY expires_at LIMIT 500 FOR UPDATE SKIP LOCKED) "
      + "UPDATE browser_sessions SET revoked_at=now(),revocation_pending=true FROM expired "
      + "WHERE browser_sessions.id_hash=expired.id_hash",
  );
  const rows = await database().query<{ id_hash: string }>(
    "SELECT id_hash FROM browser_sessions WHERE revocation_pending=true ORDER BY created_at LIMIT 50",
  );
  let revoked = 0;
  for (const row of rows.rows) {
    try {
      await withBrowserHash(row.id_hash, async (state, client) => {
        if (!state.provider || !state.tokens) throw new AuthError("unavailable");
        if (state.revokeProvider !== false) await bridgeRequest("logout", { session_id: state.provider.sessionId });
        await revokeGrant(state.tokens.refreshToken);
        await client.query("UPDATE browser_sessions SET revocation_pending=false WHERE id_hash=$1", [row.id_hash]);
      }, true);
      revoked++;
    } catch (error) {
      console.error("provider revocation pending", error instanceof AuthError ? error.code : "upstream");
    }
  }
  await database().query(
    "WITH expired AS (SELECT id_hash FROM browser_sessions WHERE authenticated=false AND expires_at<=now() "
      + "ORDER BY expires_at LIMIT 500 FOR UPDATE SKIP LOCKED) "
      + "DELETE FROM browser_sessions USING expired WHERE browser_sessions.id_hash=expired.id_hash",
  );
  await database().query(
    "WITH retired AS (SELECT id_hash FROM browser_sessions WHERE revoked_at<now()-interval '1 hour' "
      + "AND revocation_pending=false ORDER BY revoked_at LIMIT 500 FOR UPDATE SKIP LOCKED) "
      + "DELETE FROM browser_sessions USING retired WHERE browser_sessions.id_hash=retired.id_hash",
  );
  const pending = await database().query<{ count: string }>(
    "SELECT count(*) AS count FROM browser_sessions WHERE revocation_pending=true",
  );
  const count = Number(pending.rows[0].count);
  if (!Number.isSafeInteger(count)) throw new AuthError("unavailable");
  return { revoked, pending: count };
}
