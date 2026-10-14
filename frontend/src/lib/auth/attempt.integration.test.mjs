import { expect, test } from "bun:test";
import { randomUUID } from "node:crypto";
import { cancelLoginAttempt } from "./attempt";
import { authConfig } from "./config";
import { emptyJar } from "./provider";
import { bindFlow, claimTransaction, createBrowser, createTransaction, database, identifier, identifierHash,
  transactionFor, withBrowser, closeDatabase } from "./store";
import { newTransaction } from "./oauth";

const integration = process.env.AUTH_ATTEMPT_INTEGRATION === "1" ? test : test.skip;

integration("durable cancellation/expiry reject replay and preserve a newer attempt and established step-up session", async () => {
  expect(authConfig().origin).toBe("http://127.0.0.1:3000");
  const created = [];
  async function attempt(previousSessionHash) {
    const csrf = identifier();
    const transaction = newTransaction("/account");
    const browser = await createBrowser({ jar: emptyJar(), csrf, returnTo: "/account", transactionState: transaction.state,
      revokeProvider: !previousSessionHash,
      ...(previousSessionHash ? { previousSessionHash } : {}) });
    created.push(identifierHash(browser.id));
    await createTransaction(browser.id, transaction);
    const flow = randomUUID();
    await withBrowser(browser.id, async (_state, client) =>
      bindFlow(browser.id, flow, "login", new Date(Date.now() + 600000).toISOString(), client));
    return { ...browser, csrf, flow, transaction };
  }
  try {
    const a = await attempt();
    const b = await attempt();
    expect(await cancelLoginAttempt(a.id, { flow: a.flow, csrf: a.csrf })).toBe(true);
    await expect(withBrowser(a.id, async () => true)).rejects.toMatchObject({ code: "expired" });
    await expect(transactionFor(a.id, a.transaction.state)).rejects.toMatchObject({ code: "expired" });
    await expect(claimTransaction(a.id, a.transaction.state)).rejects.toMatchObject({ code: "invalid_request" });
    await expect(cancelLoginAttempt(b.id, { flow: a.flow, csrf: a.csrf })).rejects.toMatchObject({ code: "forbidden" });
    expect(await withBrowser(b.id, async () => true)).toBe(true);
    expect(await transactionFor(b.id, b.transaction.state)).toEqual(b.transaction);
    const session = await createBrowser({ jar: emptyJar(), csrf: identifier(), returnTo: "/account" }, true);
    created.push(identifierHash(session.id));
    const stepUp = await attempt(identifierHash(session.id));
    await cancelLoginAttempt(stepUp.id, { flow: stepUp.flow, csrf: stepUp.csrf });
    expect(await withBrowser(session.id, async (_state, _client, row) => row.authenticated)).toBe(true);
    const expired = await attempt();
    await database().query("UPDATE browser_sessions SET expires_at=now()-interval '1 second' WHERE id_hash=$1",
      [identifierHash(expired.id)]);
    await expect(withBrowser(expired.id, async () => true)).rejects.toMatchObject({ code: "expired" });
    await expect(claimTransaction(expired.id, expired.transaction.state)).rejects.toMatchObject({ code: "invalid_request" });
    const expiry = await database().query("SELECT expires_at-created_at AS lifetime FROM browser_sessions WHERE id_hash=$1",
      [identifierHash(b.id)]);
    expect(expiry.rows[0].lifetime.minutes).toBeLessThanOrEqual(10);
  } finally {
    await database().query("DELETE FROM browser_sessions WHERE id_hash=ANY($1::text[])", [created]);
    await closeDatabase();
  }
}, 30000);
