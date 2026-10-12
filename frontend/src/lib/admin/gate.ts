import "server-only";
import { headers } from "next/headers";
import { authConfig } from "@/lib/auth/config";
import { AuthError, authCookie, hasSessionCookie } from "@/lib/auth/model";
import { authenticatedState } from "@/lib/auth/session";
import { hiveConfig } from "@/lib/graphql/server";
import { adminPolicy } from "./policy";

export type AdminSession = { accessToken: string; username: string };
export type AdminAccess =
  | { kind: "ok"; session: AdminSession }
  | { kind: "anonymous" }
  | { kind: "forbidden"; reason: "role" | "assurance" }
  | { kind: "unavailable" };

function localOnlyAal1(): boolean {
  if (process.env.ADMIN_ALLOW_AAL1 !== "1") return false;
  const origin = new URL(process.env.STOREFRONT_ORIGIN ?? "http://invalid");
  return origin.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(origin.hostname);
}

export async function adminAccess(): Promise<AdminAccess> {
  const request = await headers();
  if (!hasSessionCookie(request.get("cookie"))) return { kind: "anonymous" };
  try {
    const cookie = authCookie(request.get("cookie"), authConfig().sessionCookie);
    if (!cookie) return { kind: "anonymous" };
    const { state, user } = await authenticatedState(cookie);
    const verdict = adminPolicy(user.role, state.provider?.aal ?? "", localOnlyAal1());
    if (verdict !== "ok") return { kind: "forbidden", reason: verdict };
    if (!state.tokens) return { kind: "anonymous" };
    return { kind: "ok", session: { accessToken: state.tokens.accessToken, username: user.username } };
  } catch (error) {
    if (error instanceof AuthError && error.status === 401) return { kind: "anonymous" };
    console.error("admin access check failed", error instanceof AuthError ? error.code : "upstream");
    return { kind: "unavailable" };
  }
}

export async function adminGraphql<T>(accessToken: string, query: string, variables: Record<string, unknown> = {}): Promise<T> {
  const { endpoint, origin } = hiveConfig(process.env);
  const response = await fetch(endpoint, {
    method: "POST",
    cache: "no-store",
    headers: { "Content-Type": "application/json", Accept: "application/json", Authorization: `Bearer ${accessToken}`, Origin: origin },
    body: JSON.stringify({ query, variables }),
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error(`Admin request failed (${response.status})`);
  const body = (await response.json()) as { data?: T; errors?: { message: string }[] };
  if (body.errors?.length) throw new Error(body.errors[0].message);
  if (!body.data) throw new Error("Empty GraphQL response");
  return body.data;
}
