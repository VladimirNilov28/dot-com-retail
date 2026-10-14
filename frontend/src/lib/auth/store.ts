import "server-only";
import { createHash, createHmac, randomBytes } from "node:crypto";
import { CompactEncrypt, compactDecrypt } from "jose";
import { Pool, type PoolClient } from "pg";
import { object } from "@/lib/graphql/scalars";
import { authConfig } from "./config";
import { AuthError, decodeBrowserState, decodeTransaction, opaqueIdentifier,
  type BrowserState, type OAuthTransaction } from "./model";

let pool: Pool | undefined;

export function identifier(): string {
  return randomBytes(32).toString("base64url");
}

export function identifierHash(value: string): string {
  return createHash("sha256").update(opaqueIdentifier(value)).digest("hex");
}

export function database(): Pool {
  if (!pool) {
    pool = new Pool({
      connectionString: authConfig().databaseUrl, max: 8,
      connectionTimeoutMillis: 5000, idleTimeoutMillis: 30000,
      options: "-c statement_timeout=15000 -c idle_in_transaction_session_timeout=30000",
    });
    pool.on("error", () => { console.error("Authentication database connection unavailable"); });
  }
  return pool;
}

export async function closeDatabase(): Promise<void> {
  await pool?.end();
  pool = undefined;
}

async function seal(purpose: string, id: string, value: unknown): Promise<string> {
  const config = authConfig();
  const key = config.keys.get(config.activeKey);
  if (!key) throw new AuthError("unavailable");
  return new CompactEncrypt(new TextEncoder().encode(JSON.stringify({ purpose, id, value })))
    .setProtectedHeader({ alg: "dir", enc: "A256GCM", kid: config.activeKey }).encrypt(key);
}

async function unseal(purpose: string, id: string, sealed: string): Promise<unknown> {
  const config = authConfig();
  const decrypted = await compactDecrypt(sealed, (header) => {
    const key = typeof header.kid === "string" ? config.keys.get(header.kid) : undefined;
    if (!key) throw new AuthError("unavailable");
    return key;
  }, { keyManagementAlgorithms: ["dir"], contentEncryptionAlgorithms: ["A256GCM"] });
  const envelope = object(JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(decrypted.plaintext)));
  if (envelope.purpose !== purpose || envelope.id !== id) throw new AuthError("unavailable");
  return envelope.value;
}

interface BrowserRow {
  sealed: string;
  authenticated: boolean;
  expires_at: Date;
  idle_until: Date;
  revoked_at: Date | null;
  revocation_pending: boolean;
}

export async function createBrowser(
  state: BrowserState, authenticated = false, client?: PoolClient,
): Promise<{ id: string; expiresAt: Date }> {
  const id = identifier();
  const hash = identifierHash(id);
  const expiresAt = new Date(Math.min(
    Date.now() + (authenticated ? 86400000 : 1800000),
    state.provider ? Date.parse(state.provider.expiresAt) : Infinity,
  ));
  await (client ?? database()).query(
    "INSERT INTO browser_sessions(id_hash,sealed,authenticated,expires_at,idle_until) VALUES($1,$2,$3,$4,$5)",
    [hash, await seal("browser", hash, state), authenticated, expiresAt,
      new Date(Math.min(expiresAt.getTime(), Date.now() + (authenticated ? 3600000 : 1800000)))],
  );
  return { id, expiresAt };
}

export async function withBrowser<T>(
  id: string, callback: (state: BrowserState, client: PoolClient, row: BrowserRow) => Promise<T>,
  inactive = false,
): Promise<T> {
  return withBrowserHash(identifierHash(id), callback, inactive);
}

export async function withBrowserHash<T>(
  hash: string, callback: (state: BrowserState, client: PoolClient, row: BrowserRow) => Promise<T>,
  inactive = false,
): Promise<T> {
  if (!/^[0-9a-f]{64}$/.test(hash)) throw new AuthError("invalid_request", 400);
  const client = await database().connect();
  try {
    await client.query("BEGIN");
    const rows = await client.query<BrowserRow>("SELECT * FROM browser_sessions WHERE id_hash=$1 FOR UPDATE", [hash]);
    const row = rows.rows[0];
    if (!row || (!inactive && (row.revoked_at || row.expires_at.getTime() <= Date.now() ||
        row.idle_until.getTime() <= Date.now()))) throw new AuthError("expired", 401);
    const state = decodeBrowserState(await unseal("browser", hash, row.sealed));
    const result = await callback(state, client, row);
    await client.query(
      "UPDATE browser_sessions SET sealed=$2,idle_until=LEAST(expires_at,$3) WHERE id_hash=$1",
      [hash, await seal("browser", hash, state), new Date(Date.now() + (row.authenticated ? 3600000 : 1800000))],
    );
    await client.query("COMMIT");
    return result;
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

export async function revokeBrowser(id: string, client?: PoolClient): Promise<void> {
  await (client ?? database()).query(
    "UPDATE browser_sessions SET revoked_at=COALESCE(revoked_at,now()),"
      + "revocation_pending=CASE WHEN revoked_at IS NULL THEN authenticated ELSE revocation_pending END WHERE id_hash=$1",
    [identifierHash(id)],
  );
}

export async function retirePrevious(hash: string, providerSessionId: string, client: PoolClient): Promise<void> {
  if (!/^[0-9a-f]{64}$/.test(hash)) throw new AuthError("invalid_request", 400);
  const result = await client.query<BrowserRow>("SELECT * FROM browser_sessions WHERE id_hash=$1 FOR UPDATE", [hash]);
  const row = result.rows[0];
  if (!row || !row.authenticated) throw new AuthError("expired", 401);
  const state = decodeBrowserState(await unseal("browser", hash, row.sealed));
  state.revokeProvider = state.provider?.sessionId !== providerSessionId;
  await client.query(
    "UPDATE browser_sessions SET sealed=$2,revoked_at=COALESCE(revoked_at,now()),revocation_pending=true WHERE id_hash=$1",
    [hash, await seal("browser", hash, state)],
  );
}

export async function removePreauth(id: string, client?: PoolClient): Promise<void> {
  await (client ?? database()).query("DELETE FROM browser_sessions WHERE id_hash=$1 AND authenticated=false", [identifierHash(id)]);
}

export async function createTransaction(browser: string, transaction: OAuthTransaction, client?: PoolClient): Promise<void> {
  const hash = identifierHash(transaction.state);
  await (client ?? database()).query(
    "INSERT INTO oauth_transactions(state_hash,browser_hash,sealed,expires_at) VALUES($1,$2,$3,$4)",
    [hash, identifierHash(browser), await seal("oauth", hash, transaction), new Date(Date.now() + 600000)],
  );
  await (client ?? database()).query(
    "UPDATE browser_sessions SET expires_at=LEAST(expires_at,now()+interval '10 minutes'),"
      + "idle_until=LEAST(idle_until,now()+interval '10 minutes') WHERE id_hash=$1 AND authenticated=false",
    [identifierHash(browser)],
  );
}

export async function claimTransaction(browser: string, state: string, client?: PoolClient): Promise<OAuthTransaction> {
  const hash = identifierHash(state);
  const result = await (client ?? database()).query<{ sealed: string }>(
    "UPDATE oauth_transactions SET used_at=now() WHERE state_hash=$1 AND browser_hash=$2 "
      + "AND used_at IS NULL AND expires_at>now() AND EXISTS(SELECT 1 FROM browser_sessions b "
      + "WHERE b.id_hash=browser_hash AND b.revoked_at IS NULL AND b.expires_at>now() AND b.idle_until>now()) RETURNING sealed",
    [hash, identifierHash(browser)],
  );
  if (result.rowCount !== 1) throw new AuthError("invalid_request", 400);
  return decodeTransaction(await unseal("oauth", hash, result.rows[0].sealed));
}

export async function transactionFor(browser: string, state: string, client?: PoolClient): Promise<OAuthTransaction> {
  const hash = identifierHash(state);
  const result = await (client ?? database()).query<{ sealed: string }>(
    "SELECT sealed FROM oauth_transactions WHERE state_hash=$1 AND browser_hash=$2 "
      + "AND used_at IS NULL AND expires_at>now()", [hash, identifierHash(browser)],
  );
  if (result.rowCount !== 1) throw new AuthError("expired", 401);
  return decodeTransaction(await unseal("oauth", hash, result.rows[0].sealed));
}

export async function bindFlow(browser: string, id: string, kind: string, expiresAt: string, client: PoolClient): Promise<void> {
  await client.query(
    "INSERT INTO browser_flows(flow_id,browser_hash,kind,expires_at) VALUES($1,$2,$3,$4) "
      + "ON CONFLICT(flow_id) DO NOTHING", [id, identifierHash(browser), kind, new Date(expiresAt)],
  );
}

export async function assertFlow(client: PoolClient, browser: string, id: string, kind: string): Promise<void> {
  const result = await client.query(
    "SELECT flow_id FROM browser_flows WHERE flow_id=$1 AND browser_hash=$2 AND kind=$3 AND expires_at>now()",
    [id, identifierHash(browser), kind],
  );
  if (result.rowCount !== 1) throw new AuthError("expired", 401);
}

/**
 * Count one attempt in a fixed window, outside the caller's transaction so a
 * rejected request cannot roll its own attempt back. Subjects are keyed with
 * the active seal key; no raw identifier is stored.
 */
export async function consumeAttempt(scope: string, subject: string, limit: number, windowSeconds: number): Promise<boolean> {
  const config = authConfig();
  const key = config.keys.get(config.activeKey);
  if (!key) throw new AuthError("unavailable");
  const hash = createHmac("sha256", key).update(`attempt\u0000${scope}\u0000${subject}`).digest("hex");
  const result = await database().query<{ attempts: number }>(
    "INSERT INTO attempt_limits(key_hash,window_start,attempts) VALUES($1,now(),1) "
      + "ON CONFLICT(key_hash) DO UPDATE SET "
      + "attempts=CASE WHEN attempt_limits.window_start<=now()-make_interval(secs=>$2) THEN 1 ELSE attempt_limits.attempts+1 END,"
      + "window_start=CASE WHEN attempt_limits.window_start<=now()-make_interval(secs=>$2) THEN now() ELSE attempt_limits.window_start END "
      + "RETURNING attempts", [hash, windowSeconds],
  );
  if (Math.random() < 0.01) {
    await database().query("DELETE FROM attempt_limits WHERE window_start<now()-interval '1 day'");
  }
  return result.rows[0].attempts <= limit;
}
