import "server-only";
import { timingSafeEqual } from "node:crypto";
import { NextRequest, NextResponse } from "next/server";
import { authConfig, authOrigin } from "./config";
import { AuthError, authCookie, formKind, oneParameter, safeReturnTo } from "./model";

export const authHeaders = {
  "Cache-Control": "no-store, private",
  Pragma: "no-cache",
  "X-Robots-Tag": "noindex, nofollow",
  "Referrer-Policy": "same-origin",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
};

export function browserCookie(request: NextRequest, authenticated = false): string {
  const config = authConfig();
  const value = authCookie(request.headers.get("cookie"), authenticated ? config.sessionCookie : config.flowCookie);
  if (!value) throw new AuthError("expired", 401);
  return value;
}

export function redirectResponse(destination: string | URL): NextResponse {
  return NextResponse.redirect(new URL(destination, authConfig().origin), { status: 303, headers: authHeaders });
}

export function setBrowserCookie(response: NextResponse, id: string, expires: Date, authenticated = false): void {
  const config = authConfig();
  response.cookies.set(authenticated ? config.sessionCookie : config.flowCookie, id, {
    httpOnly: true, secure: config.secure, sameSite: "lax", path: "/", expires,
  });
}

export function clearBrowserCookies(response: NextResponse, authenticated = true): void {
  const config = authConfig();
  for (const name of authenticated ? [config.sessionCookie, config.flowCookie] : [config.flowCookie]) {
    response.cookies.set(name, "", { httpOnly: true, secure: config.secure, sameSite: "lax", path: "/", maxAge: 0 });
  }
}

export async function boundedForm(request: Request): Promise<URLSearchParams> {
  const declaredLength = request.headers.get("content-length");
  if (request.headers.get("content-type")?.split(";", 1)[0] !== "application/x-www-form-urlencoded" ||
      !request.body || (declaredLength !== null && (!/^(?:0|[1-9][0-9]*)$/.test(declaredLength) ||
        Number(declaredLength) > 65536))) {
    throw new AuthError("invalid_request", 400);
  }
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  let timedOut = false;
  const timeout = setTimeout(() => {
    timedOut = true;
    void reader.cancel().catch(() => console.error("authentication form cancellation failed"));
  }, 8000);
  try {
    for (;;) {
      const chunk = await reader.read();
      if (chunk.done) break;
      length += chunk.value.length;
      if (length > 65536) {
        await reader.cancel();
        throw new AuthError("invalid_request", 413);
      }
      chunks.push(chunk.value);
    }
    if (timedOut) throw new AuthError("invalid_request", 408);
    if (declaredLength !== null && Number(declaredLength) !== length) throw new AuthError("invalid_request", 400);
    const body = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.length; }
    return new URLSearchParams(new TextDecoder("utf-8", { fatal: true }).decode(body));
  } finally {
    clearTimeout(timeout);
    reader.releaseLock();
  }
}

export function assertCsrf(request: Request, form: URLSearchParams, expected: string): void {
  if (request.headers.get("origin") !== authConfig().origin) throw new AuthError("forbidden", 403);
  const supplied = oneParameter(form, "_csrf");
  if (!supplied || !/^[A-Za-z0-9_-]{43}$/.test(supplied) ||
      !timingSafeEqual(Buffer.from(supplied), Buffer.from(expected))) throw new AuthError("forbidden", 403);
}

export async function authBoundary(
  callback: () => Promise<NextResponse>, operation = "request", request?: Request,
  recoverReturnTo?: () => Promise<string | undefined>,
): Promise<NextResponse> {
  try {
    return await callback();
  } catch (error) {
    const code = error instanceof AuthError ? error.code : "unavailable";
    console.error("authentication request rejected", operation, code);
    if (request?.headers.get("accept")?.includes("text/html")) {
      const query = new URLSearchParams({ reason: code, kind: "login", returnTo: "/" });
      try {
        const parameters = new URL(request.url).searchParams;
        query.set("kind", formKind(oneParameter(parameters, "kind") ?? "login"));
        query.set("returnTo", safeReturnTo(oneParameter(parameters, "returnTo")));
        const boundReturnTo = await recoverReturnTo?.();
        if (boundReturnTo !== undefined) query.set("returnTo", safeReturnTo(boundReturnTo));
      } catch (recoveryError) {
        console.error("authentication recovery context unavailable",
          recoveryError instanceof AuthError ? recoveryError.code : "upstream");
      }
      try {
        return NextResponse.redirect(new URL(`/auth/error?${query}`, authOrigin()), { status: 303, headers: authHeaders });
      } catch (originError) {
        if (!(originError instanceof AuthError) && !(originError instanceof TypeError)) throw originError;
        console.error("authentication public origin unavailable");
      }
    }
    return NextResponse.json({ error: code }, {
      status: error instanceof AuthError ? error.status : 503, headers: authHeaders,
    });
  }
}
