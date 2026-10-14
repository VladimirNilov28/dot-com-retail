import "server-only";
import { AuthError } from "./model";
import { bridgeRequest, emptyJar, providerClient, providerFailure } from "./provider";
import { identifierHash, withBrowser, withBrowserHash } from "./store";

export async function finishCancelledAttempt(hash: string): Promise<void> {
  await withBrowserHash(hash, async (state, client, row) => {
    if (row.authenticated || !row.revoked_at) throw new AuthError("forbidden", 403);
    if (state.loginChallenge && state.transactionState) {
      await bridgeRequest("login/cancel", { challenge: state.loginChallenge, state: state.transactionState });
    }
    // Step-up borrowed an established session; never revoke it on cancellation.
    // Legacy attempts with a previous session also borrowed its provider jar.
    if (state.revokeProvider ?? !state.previousSessionHash) {
      const provider = providerClient(state);
      try {
        const logout = (await provider.api.createBrowserLogoutFlow()).data;
        await provider.api.updateLogoutFlow({ token: logout.logout_token });
      } catch (error) {
        const failure = providerFailure(error);
        if (![401, 410].includes(failure.status)) throw error;
      }
    }
    state.jar = emptyJar();
    delete state.social;
    delete state.googleRegistration;
    delete state.provider;
    delete state.loginChallenge;
    delete state.transactionState;
    await client.query("UPDATE browser_sessions SET revocation_pending=false WHERE id_hash=$1", [hash]);
  }, true);
}

export async function cancelLoginAttempt(browser: string, expected?: { flow: string; csrf: string }): Promise<boolean> {
  const cancelled = await withBrowser(browser, async (state, client, row) => {
    if (row.authenticated) return false;
    if (expected) {
      if (state.csrf !== expected.csrf) throw new AuthError("forbidden", 403);
      const binding = await client.query("SELECT 1 FROM browser_flows WHERE browser_hash=$1 AND flow_id=$2 AND kind='login'",
        [identifierHash(browser), expected.flow]);
      if (binding.rowCount !== 1) throw new AuthError("forbidden", 403);
    }
    if (row.revoked_at) return false;
    if (!state.transactionState) return false;
    await client.query("UPDATE browser_sessions SET revoked_at=now(),revocation_pending=true WHERE id_hash=$1 AND authenticated=false",
      [identifierHash(browser)]);
    await client.query("UPDATE oauth_transactions SET used_at=COALESCE(used_at,now()),expires_at=LEAST(expires_at,now()) WHERE browser_hash=$1",
      [identifierHash(browser)]);
    await client.query("UPDATE browser_flows SET expires_at=LEAST(expires_at,now()) WHERE browser_hash=$1", [identifierHash(browser)]);
    return true;
  }, true);
  if (cancelled) {
    try { await finishCancelledAttempt(identifierHash(browser)); }
    catch { console.error("Cancelled login provider cleanup pending; transaction remains revoked"); }
  }
  return cancelled;
}
