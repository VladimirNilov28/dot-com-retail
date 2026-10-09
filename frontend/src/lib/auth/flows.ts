import "server-only";
import type { PoolClient } from "pg";
import { authConfig } from "./config";
import { AuthError, type BrowserState, type FormKind } from "./model";
import { providerClient, providerFailure } from "./provider";
import { assertFlow, bindFlow } from "./store";
import { object, text, uuid } from "@/lib/graphql/scalars";

export interface ProviderFlow {
  id: string;
  expiresAt: string;
  state?: string;
  nodes: Record<string, unknown>[];
  messages: Record<string, unknown>[];
}

function validateFlow(value: unknown, kind: FormKind): ProviderFlow {
  const flow = object(value);
  const ui = object(flow.ui);
  const config = authConfig();
  const action = new URL(text(ui.action));
  const id = uuid(flow.id);
  const expiresAt = text(flow.expires_at);
  const providerKind = kind === "registration" ? "registration" : kind;
  const verified = kind === "verification" && flow.state === "passed_challenge";
  const validAction = verified
    ? action.origin === config.origin && action.pathname === "/auth/resume" &&
      !action.search && text(ui.method).toUpperCase() === "GET"
    : action.origin === config.kratos && action.pathname === `/self-service/${providerKind}` &&
      action.searchParams.getAll("flow").length === 1 && action.searchParams.get("flow") === id &&
      text(ui.method).toUpperCase() === "POST";
  if (flow.type !== "browser" || !Number.isFinite(Date.parse(expiresAt)) ||
      !validAction || action.username || action.password || action.hash ||
      !Array.isArray(ui.nodes) || ui.nodes.length > 100 ||
      (ui.messages !== undefined && !Array.isArray(ui.messages))) {
    throw new AuthError("unavailable");
  }
  if (Date.parse(expiresAt) <= Date.now()) throw new AuthError("expired", 401);
  return {
    id, expiresAt, nodes: ui.nodes.map(object), messages: (ui.messages ?? []).map(object),
    ...(flow.state !== undefined ? { state: text(flow.state) } : {}),
  };
}

export async function initializeFlow(
  state: BrowserState, browser: string, kind: FormKind, client: PoolClient, aal: "aal1" | "aal2" = "aal1",
): Promise<ProviderFlow> {
  const provider = providerClient(state);
  try {
    const config = authConfig();
    const flow = kind === "login"
      ? (await provider.api.createBrowserLoginFlow({
        aal, refresh: aal === "aal2", returnTo: `${config.origin}/auth/resume`,
      })).data
      : kind === "registration"
        ? (await provider.api.createBrowserRegistrationFlow({
          returnTo: `${config.origin}/verify`, afterVerificationReturnTo: `${config.origin}/auth/resume`,
          identitySchema: "customer-v1",
        })).data
        : (await provider.api.createBrowserVerificationFlow({ returnTo: `${config.origin}/auth/resume` })).data;
    const validated = validateFlow(flow, kind);
    await bindFlow(browser, validated.id, kind, validated.expiresAt, client);
    return validated;
  } catch (error) {
    if (error instanceof AuthError) throw error;
    const failure = providerFailure(error);
    if (failure.status === 400 || failure.status === 410) throw new AuthError("expired", 401);
    throw new AuthError("unavailable");
  } finally {
    provider.save();
  }
}

export async function readFlow(
  state: BrowserState, browser: string, id: string, kind: FormKind, client: PoolClient,
): Promise<ProviderFlow> {
  await assertFlow(client, browser, uuid(id), kind);
  const provider = providerClient(state);
  try {
    const flow = kind === "login" ? (await provider.api.getLoginFlow({ id })).data
      : kind === "registration" ? (await provider.api.getRegistrationFlow({ id })).data
        : (await provider.api.getVerificationFlow({ id })).data;
    return validateFlow(flow, kind);
  } catch (error) {
    if (error instanceof AuthError) throw error;
    const failure = providerFailure(error);
    if ([400, 403, 404, 410].includes(failure.status)) throw new AuthError("expired", 401);
    throw new AuthError("unavailable");
  } finally {
    provider.save();
  }
}

export type Submission = { complete: true; verificationFlow?: string } |
  { complete: false; validation: boolean; flow: ProviderFlow };

function field(form: URLSearchParams, name: string, maximum: number, optional = false): string {
  const values = form.getAll(name);
  if (values.length > 1 || (!optional && values.length !== 1) ||
      (values[0] && (values[0].length > maximum || /[\u0000\r\n]/.test(values[0])))) {
    throw new AuthError("invalid_request", 400);
  }
  return values[0] ?? "";
}

export async function submitFlow(
  state: BrowserState, browser: string, id: string, kind: FormKind, form: URLSearchParams, client: PoolClient,
): Promise<Submission> {
  const flow = await readFlow(state, browser, id, kind, client);
  const method = field(form, "method", 32);
  if (!flow.nodes.some((node) => node.type === "input" &&
      object(node.attributes).name === "method" && object(node.attributes).value === method)) {
    throw new AuthError("invalid_request", 400);
  }
  const csrf_token = field(form, "csrf_token", 1024);
  const provider = providerClient(state);
  try {
    let result: unknown;
    if (kind === "login") {
      if (method === "password") {
        result = (await provider.api.updateLoginFlow({ flow: id, updateLoginFlowBody: {
          method, csrf_token, identifier: field(form, "identifier", 255), password: field(form, "password", 4096),
        } })).data;
      } else if (method === "totp") {
        result = (await provider.api.updateLoginFlow({ flow: id, updateLoginFlowBody: {
          method, csrf_token, totp_code: field(form, "totp_code", 64),
        } })).data;
      } else if (method === "lookup_secret") {
        result = (await provider.api.updateLoginFlow({ flow: id, updateLoginFlowBody: {
          method, csrf_token, lookup_secret: field(form, "lookup_secret", 128),
        } })).data;
      } else throw new AuthError("invalid_request", 400);
    } else if (kind === "registration") {
      if (method !== "password") throw new AuthError("invalid_request", 400);
      result = (await provider.api.updateRegistrationFlow({ flow: id, updateRegistrationFlowBody: {
        method, csrf_token, password: field(form, "password", 4096),
        traits: {
          email: field(form, "traits.email", 255), username: field(form, "traits.username", 255),
          dateOfBirth: field(form, "traits.dateOfBirth", 10),
        },
        transient_payload: { altcha: field(form, "altcha", 16384) },
      } })).data;
    } else {
      if (method !== "code") throw new AuthError("invalid_request", 400);
      const code = field(form, "code", 128, true);
      const email = field(form, "email", 255, true);
      result = (await provider.api.updateVerificationFlow({ flow: id, updateVerificationFlowBody: {
        method, csrf_token, ...(code ? { code } : { email }),
      } })).data;
      const verification = validateFlow(result, kind);
      return verification.state === "passed_challenge" ? { complete: true } :
        { complete: false, validation: false, flow: verification };
    }
    const body = object(result);
    const continuations = Array.isArray(body.continue_with) ? body.continue_with : [];
    for (const item of continuations) {
      const continuation = object(item);
      if (continuation.action === "show_verification_ui") {
        const target = object(continuation.flow);
        const verificationFlow = uuid(target.id);
        const verification = validateFlow((await provider.api.getVerificationFlow({ id: verificationFlow })).data, "verification");
        await bindFlow(browser, verification.id, "verification", verification.expiresAt, client);
        return { complete: true, verificationFlow };
      }
    }
    return { complete: true };
  } catch (error) {
    if (error instanceof AuthError) throw error;
    const failure = providerFailure(error);
    console.error("provider form response", kind, failure.status);
    if (failure.status === 400) {
      const rejected = validateFlow(failure.body, kind);
      if (rejected.id !== flow.id) throw new AuthError("invalid_request", 400);
      return { complete: false, validation: true, flow: rejected };
    }
    if (failure.status === 410) throw new AuthError("expired", 401);
    if (failure.status === 422) {
      const redirect = object(failure.body).redirect_browser_to;
      const config = authConfig();
      if (typeof redirect !== "string") throw new AuthError("unavailable");
      const url = new URL(redirect);
      console.error("provider completion destination", kind, url.pathname, Array.from(url.searchParams.keys()).join(","));
      if (kind === "login" && url.origin === config.kratos && url.pathname === "/self-service/login/browser" &&
          url.searchParams.getAll("aal").length === 1 && url.searchParams.get("aal") === "aal2" &&
          url.searchParams.getAll("return_to").length === 1 &&
          url.searchParams.get("return_to") === `${config.origin}/auth/resume` &&
          Array.from(url.searchParams.keys()).every((key) => key === "aal" || key === "return_to") &&
          !url.hash && !url.username && !url.password) {
        // Save the provisional provider jar, then let the existing bridge
        // verify assurance and initialize its own bound second-factor flow.
        return { complete: true };
      }
      if (url.origin === config.origin && url.pathname === "/auth/resume" &&
          !url.search && !url.hash && !url.username && !url.password) {
        if (kind === "login") return { complete: true };
        if (kind === "verification") {
          const verified = validateFlow((await provider.api.getVerificationFlow({ id })).data, kind);
          if (verified.state === "passed_challenge") return { complete: true };
        }
      }
      if (url.origin === config.origin && url.pathname === "/verify" && url.searchParams.get("flow")) {
        const id = uuid(url.searchParams.get("flow"));
        const verification = validateFlow((await provider.api.getVerificationFlow({ id })).data, "verification");
        await bindFlow(browser, id, "verification", verification.expiresAt, client);
        return { complete: true, verificationFlow: id };
      }
    }
    throw new AuthError("unavailable");
  } finally {
    provider.save();
  }
}
