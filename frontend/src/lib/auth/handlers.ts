import "server-only";
import { NextRequest, NextResponse } from "next/server";
import { authConfig } from "./config";
import { AuthError, accountSection, assertSameProvider, decodeProviderSession, flowIdentifier, formKind, grantTokens, hasSessionCookie,
  oneParameter, safeReturnTo, type BrowserState, type FormKind } from "./model";
import { object } from "@/lib/graphql/scalars";
import { bridgeRequest, currentProviderSession, emptyJar, providerClient, providerCookie, providerRedirect } from "./provider";
import { authorizationUrl, exchangeCode, newTransaction } from "./oauth";
import { initializeFlow, submitFlow } from "./flows";
import { createBrowser, createTransaction, claimTransaction, identifier, identifierHash,
  removePreauth, retirePrevious, revokeBrowser, transactionFor, withBrowser, assertFlow } from "./store";
import { authenticatedState, readCurrentUser, revokeSession } from "./session";
import { handleSecuritySettings } from "./settings";
import { handleAccount, notifySecurityEvent, recoveryMode, accountStatus, sessionFacts } from "./account";
import { assertBrowserNavigation, assertCsrf, authBoundary, authHeaders, boundedForm, browserCookie,
  clearBrowserCookies, redirectResponse, setBrowserCookie } from "./http";
import { cancelLoginAttempt } from "./attempt";

function flowRoute(kind: FormKind): string {
  return kind === "registration" ? "/register" : kind === "verification" ? "/verify" :
    kind === "recovery" ? "/recover" : kind === "reset" ? "/reset" : "/login";
}

async function start(request: NextRequest, securityReauth = false): Promise<NextResponse> {
  const returnTo = securityReauth ? accountSection(oneParameter(request.nextUrl.searchParams, "section"))
    : safeReturnTo(oneParameter(request.nextUrl.searchParams, "returnTo"));
  let jar = emptyJar();
  let previousSessionHash: string | undefined;
  let assurance: "aal1" | "aal2" = "aal1";
  if (request.cookies.has(authConfig().sessionCookie)) {
    const previous = browserCookie(request, true);
    try {
      jar = await withBrowser(previous, async (state, _client, row) => {
        if (!row.authenticated) throw new AuthError("expired", 401);
        assurance = state.provider?.aal ?? "aal1";
        return securityReauth ? state.jar : emptyJar();
      });
      previousSessionHash = identifierHash(previous);
    } catch (error) {
      if (!(error instanceof AuthError) || error.code !== "expired") throw error;
    }
  }
  if (request.cookies.has(authConfig().flowCookie)) {
    try {
      await cancelLoginAttempt(browserCookie(request));
    } catch (error) {
      if (!(error instanceof AuthError) || error.code !== "expired") throw error;
    }
  }
  const transaction = newTransaction(returnTo);
  const browser = await createBrowser({
    jar, csrf: identifier(), returnTo, transactionState: transaction.state, previousSessionHash,
    securityReauth: securityReauth ? assurance : undefined,
    revokeProvider: !securityReauth || !previousSessionHash,
  });
  await createTransaction(browser.id, transaction);
  const response = redirectResponse(await authorizationUrl(transaction));
  setBrowserCookie(response, browser.id, browser.expiresAt);
  return response;
}

async function attemptAction(request: NextRequest, cancel: boolean): Promise<NextResponse> {
  const form = await boundedForm(request);
  if (request.headers.get("origin") !== authConfig().origin) throw new AuthError("forbidden", 403);
  const browser = browserCookie(request);
  const flow = flowIdentifier(oneParameter(form, "flow"));
  const csrf = oneParameter(form, "_csrf");
  if (!csrf) throw new AuthError("forbidden", 403);
  if (cancel) {
    const destination = await withBrowser(browser, async (state) =>
      (state.revokeProvider ?? !state.previousSessionHash) ? "/login" : state.returnTo, true);
    await cancelLoginAttempt(browser, { flow, csrf });
    // No cookie deletion: a late A response must not delete B's newer cookie,
    // an established login, guest-cart or checkout proof.
    return request.headers.get("accept")?.includes("text/html") ? redirectResponse(destination) :
      NextResponse.json({ cancelled: true }, { headers: authHeaders });
  }
  await withBrowser(browser, async (state, client) => {
    assertCsrf(request, form, state.csrf);
    if (!state.transactionState) throw new AuthError("expired", 401);
    await transactionFor(browser, state.transactionState, client);
    await assertFlow(client, browser, flow, "login");
  });
  return NextResponse.json({ active: true }, { headers: authHeaders });
}

async function handoff(browser: string): Promise<NextResponse> {
  return withBrowser(browser, async (state, client) => {
    if (!state.loginChallenge || !state.transactionState) throw new AuthError("expired", 401);
    await transactionFor(browser, state.transactionState, client);
    const result = object(await bridgeRequest("login", {
      challenge: state.loginChallenge, state: state.transactionState, cookie: await providerCookie(state),
    }));
    if (result.login_required === true) {
      if (result.aal !== "aal1" && result.aal !== "aal2") throw new AuthError("unavailable");
      const flow = await initializeFlow(state, browser, "login", client, result.aal);
      return redirectResponse(`/login?flow=${flow.id}&returnTo=${encodeURIComponent(state.returnTo)}`);
    }
    if (result.verification_required === true) {
      const flow = await initializeFlow(state, browser, "verification", client);
      return redirectResponse(`/verify?flow=${flow.id}&returnTo=${encodeURIComponent(state.returnTo)}`);
    }
    state.provider = decodeProviderSession(result.session);
    return redirectResponse(providerRedirect(result.redirect_to));
  });
}

async function loginChallenge(request: NextRequest): Promise<NextResponse> {
  const browser = browserCookie(request);
  const challenge = oneParameter(request.nextUrl.searchParams, "login_challenge");
  if (!challenge || challenge.length > 8192) throw new AuthError("invalid_request", 400);
  const refreshFlow = await withBrowser(browser, async (state, client) => {
    if (!state.transactionState) throw new AuthError("expired", 401);
    await transactionFor(browser, state.transactionState, client);
    await bridgeRequest("login/validate", { challenge, state: state.transactionState });
    state.loginChallenge = challenge;
    if (state.securityReauth) {
      const assurance = state.securityReauth;
      delete state.securityReauth;
      const flow = await initializeFlow(state, browser, "login", client, assurance, true);
      return { id: flow.id, returnTo: state.returnTo };
    }
  });
  if (refreshFlow) return redirectResponse(`/login?flow=${refreshFlow.id}&returnTo=${encodeURIComponent(refreshFlow.returnTo)}`);
  return handoff(browser);
}

async function consent(request: NextRequest): Promise<NextResponse> {
  const browser = browserCookie(request);
  const challenge = oneParameter(request.nextUrl.searchParams, "consent_challenge");
  if (!challenge || challenge.length > 8192) throw new AuthError("invalid_request", 400);
  return withBrowser(browser, async (state, client) => {
    if (!state.transactionState || !state.provider) throw new AuthError("expired", 401);
    await transactionFor(browser, state.transactionState, client);
    assertSameProvider(state.provider, await currentProviderSession(state));
    const result = object(await bridgeRequest("consent", { challenge, state: state.transactionState }));
    return redirectResponse(providerRedirect(result.redirect_to));
  });
}

/**
 * A second factor satisfied with a recovery code is an MFA recovery event:
 * sign out every other session, notify the account email and route the user
 * to replace the authenticator. Fails closed if other sessions cannot be revoked.
 */
async function completeRecoverySignIn(state: BrowserState): Promise<boolean> {
  if (!state.provider) throw new AuthError("expired", 401);
  const provider = providerClient(state);
  try {
    const facts = sessionFacts((await provider.api.toSession()).data, state.provider);
    const latest = facts.methods.filter((entry) => entry.method === "lookup_secret")
      .reduce((max, entry) => Math.max(max, entry.completedAt), 0);
    if (!recoveryMode(facts) || Date.now() - latest > 10 * 60 * 1000) return false;
    await provider.api.disableMyOtherSessions();
    provider.save();
    const status = await accountStatus(state);
    await notifySecurityEvent(state, "mfa_recovery_used", status.recoveryCodesRemaining);
    console.info("authentication mfa recovery sign-in completed");
    return true;
  } catch (error) {
    if (error instanceof AuthError) throw error;
    console.error("authentication mfa recovery reason", "provider_response");
    throw new AuthError("unavailable");
  } finally {
    provider.save();
  }
}

async function callback(request: NextRequest): Promise<NextResponse> {
  const config = authConfig();
  const origin = request.headers.get("origin");
  // Providers may suppress Origin on their form_post document. Bound state,
  // PKCE and nonce remain mandatory even when the protocol origin is absent.
  if (origin && origin !== "null" && origin !== config.issuer) throw new AuthError("forbidden", 403);
  const form = await boundedForm(request);
  const state = oneParameter(form, "state");
  if (!state) throw new AuthError("invalid_request", 400);
  for (const field of ["code", "error", "error_description", "iss"]) oneParameter(form, field);
  const browser = browserCookie(request);
  return withBrowser(browser, async (context, client) => {
    const transaction = await claimTransaction(browser, state, client);
    if (oneParameter(form, "error")) throw new AuthError("forbidden", 403);
    const tokens = await exchangeCode(new Request(`${config.origin}/auth/callback`, {
      method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: form.toString(),
    }), transaction);
    if (!context.provider || context.transactionState !== transaction.state ||
        tokens.claims()?.sub !== context.provider.userId) {
      throw new AuthError("invalid_request", 400);
    }
    assertSameProvider(context.provider, await currentProviderSession(context));
    await readCurrentUser(tokens.access_token, context.provider);
    const recovered = context.provider.role === "USER" && await completeRecoverySignIn(context);
    const authenticated = await createBrowser({
      jar: context.jar, csrf: identifier(), returnTo: transaction.returnTo, provider: context.provider,
      tokens: grantTokens(tokens),
    }, true, client);
    if (context.previousSessionHash) await retirePrevious(context.previousSessionHash, context.provider.sessionId, client);
    await removePreauth(browser, client);
    const response = redirectResponse(recovered ? "/account/security?recovery=1" : transaction.returnTo);
    setBrowserCookie(response, authenticated.id, authenticated.expiresAt, true);
    clearBrowserCookies(response, false);
    return response;
  });
}

async function flow(request: NextRequest): Promise<NextResponse> {
  const kind = formKind(oneParameter(request.nextUrl.searchParams, "kind"));
  if (kind === "login") return start(request);
  // Only a redeemed, browser-bound recovery continuation may expose reset.
  if (kind === "reset") throw new AuthError("forbidden", 403);
  const returnTo = safeReturnTo(oneParameter(request.nextUrl.searchParams, "returnTo"));
  let browser: string;
  let created: Awaited<ReturnType<typeof createBrowser>> | undefined;
  if (request.cookies.has(authConfig().flowCookie)) {
    try {
      browser = browserCookie(request);
      await withBrowser(browser, async () => undefined);
    } catch (error) {
      if (!(error instanceof AuthError) || error.code !== "expired") throw error;
      created = await createBrowser({ jar: emptyJar(), csrf: identifier(), returnTo });
      browser = created.id;
    }
  } else {
    created = await createBrowser({ jar: emptyJar(), csrf: identifier(), returnTo });
    browser = created.id;
  }
  const providerFlow = await withBrowser(browser, async (state, client) => {
    state.returnTo = returnTo;
    return initializeFlow(state, browser, kind, client);
  });
  const response = redirectResponse(`${flowRoute(kind)}?flow=${providerFlow.id}&returnTo=${encodeURIComponent(returnTo)}`);
  if (created) setBrowserCookie(response, created.id, created.expiresAt);
  return response;
}

async function submit(request: NextRequest): Promise<NextResponse> {
  const form = await boundedForm(request);
  const browser = browserCookie(request);
  const kind = formKind(oneParameter(request.nextUrl.searchParams, "kind"));
  const flowId = flowIdentifier(oneParameter(form, "flow"));
  const submission = await withBrowser(browser, async (state, client) => {
    assertCsrf(request, form, state.csrf);
    return { ...await submitFlow(state, browser, flowId, kind, form, client), returnTo: state.returnTo };
  });
  if (!submission.complete) return redirectResponse(`${flowRoute(kind)}?flow=${flowId}` +
    `&returnTo=${encodeURIComponent(submission.returnTo)}${submission.validation ? "&validation=1" : ""}` +
    `${submission.passwordError ? `&passwordError=${submission.passwordError}` : ""}`);
  if (submission.verificationFlow) return redirectResponse(`/verify?flow=${submission.verificationFlow}` +
    `&returnTo=${encodeURIComponent(submission.returnTo)}`);
  if (submission.settingsFlow) return redirectResponse(`/reset?flow=${submission.settingsFlow}`);
  if (submission.socialRedirect) return redirectResponse(submission.socialRedirect);
  if (submission.socialConflict) {
    const login = await withBrowser(browser, async (state, client) =>
      ({ id: (await initializeFlow(state, browser, "login", client)).id, returnTo: state.returnTo }));
    return redirectResponse(`/login?flow=${login.id}&social=conflict&returnTo=${encodeURIComponent(login.returnTo)}`);
  }
  if (submission.socialComplete) return handoff(browser);
  if (kind === "reset") {
    // A recovery session is not a storefront OAuth login. Discard it after
    // changing the credential and require a fresh password/factor login.
    await withBrowser(browser, async (state, client) => {
      const provider = providerClient(state);
      try {
        const logout = (await provider.api.createBrowserLogoutFlow()).data;
        await provider.api.updateLogoutFlow({ token: logout.logout_token });
      } finally { provider.save(); }
      await removePreauth(browser, client);
    });
    const response = redirectResponse("/login?reset=success");
    clearBrowserCookies(response);
    return response;
  }
  if (kind === "registration") return flow(new NextRequest(`${authConfig().origin}/auth/flow?kind=verification` +
    `&returnTo=${encodeURIComponent(submission.returnTo)}`, {
    headers: request.headers,
  }));
  const destination = await withBrowser(browser, async (state) => state.returnTo);
  if (kind === "verification") return redirectResponse(`/auth/start?returnTo=${encodeURIComponent(destination)}`);
  return handoff(browser);
}

async function logout(request: NextRequest): Promise<NextResponse> {
  const form = await boundedForm(request);
  const browser = browserCookie(request, true);
  await withBrowser(browser, async (state) => { assertCsrf(request, form, state.csrf); }, true);
  await revokeBrowser(browser);
  try {
    await revokeSession(browser);
    const response = redirectResponse("/");
    clearBrowserCookies(response);
    return response;
  } catch (error) {
    console.error("provider logout pending", error instanceof AuthError ? error.code : "upstream");
    const response = NextResponse.json({ error: "provider_logout_pending", signedOut: true }, {
      status: 502, headers: authHeaders,
    });
    clearBrowserCookies(response);
    if (request.headers.get("accept")?.includes("text/html")) {
      const page = redirectResponse("/auth/error?reason=provider_logout_pending");
      clearBrowserCookies(page);
      return page;
    }
    return response;
  }
}

export async function handleAuth(request: NextRequest, action: string): Promise<NextResponse> {
  if (action === "security") return handleSecuritySettings(request);
  if (action === "account") return handleAccount(request);
  return authBoundary(async () => {
    if (request.method === "GET" && action === "session") {
      if (!hasSessionCookie(request.headers.get("cookie"))) {
        return NextResponse.json({ kind: "anonymous" }, { headers: authHeaders });
      }
      const result = await authenticatedState(browserCookie(request, true));
      return NextResponse.json({
        kind: "authenticated", user: { id: result.user.id, username: result.user.username }, csrf: result.state.csrf,
      }, { headers: authHeaders });
    }
    if (request.method === "GET" && request.headers.get("host") !== new URL(authConfig().origin).host) {
      return redirectResponse(`${authConfig().origin}${request.nextUrl.pathname}${request.nextUrl.search}`);
    }
    if (request.method === "GET") {
      if (["start", "security-reauth", "flow"].includes(action)) assertBrowserNavigation(request);
      if (action === "start") return start(request);
      if (action === "security-reauth") return start(request, true);
      if (action === "challenge") return loginChallenge(request);
      if (action === "consent") return consent(request);
      if (action === "resume") {
        const browser = browserCookie(request);
        const state = await withBrowser(browser, async (state) => ({
          transaction: state.transactionState, returnTo: state.returnTo,
        }));
        return state.transaction ? handoff(browser)
          : redirectResponse(`/auth/start?returnTo=${encodeURIComponent(state.returnTo)}`);
      }
      if (action === "flow") return flow(request);
      if (action === "captcha") {
        const browser = browserCookie(request);
        const id = flowIdentifier(oneParameter(request.nextUrl.searchParams, "flow"));
        const challenge = await withBrowser(browser, async (_state, client) => {
          await assertFlow(client, browser, id, "registration");
          return bridgeRequest("captcha/challenge", { flow_id: id });
        });
        return NextResponse.json(challenge, { headers: authHeaders });
      }
    } else if (request.method === "POST") {
      if (action === "callback") return callback(request);
      if (action === "submit") return submit(request);
      if (action === "logout") return logout(request);
      if (action === "cancel" || action === "check-attempt") return attemptAction(request, action === "cancel");
    }
    return NextResponse.json({ error: "not_found" }, { status: 404, headers: authHeaders });
  }, ["start", "challenge", "consent", "resume", "flow", "captcha", "callback", "submit", "logout", "session", "cancel", "check-attempt"].includes(action)
    ? action : "unknown", request, async () => {
      const config = authConfig();
      if (!request.cookies.has(config.flowCookie)) return undefined;
      return withBrowser(browserCookie(request), async (state) => state.returnTo);
    });
}
