import { safeReturnTo } from "./model";

export type HeaderAccount = { kind: "checking" | "anonymous" | "expired" | "unavailable" } |
  { kind: "authenticated"; username: string; csrf: string; staff?: true };

export function headerReturnTo(pathname: string, search: string): string {
  const parameters = new URLSearchParams(search);
  if (pathname.startsWith("/order-confirmation/")) {
    for (const key of ["payment_intent", "payment_intent_client_secret", "redirect_status"]) parameters.delete(key);
  }
  return safeReturnTo(pathname + (parameters.size ? `?${parameters}` : ""));
}

export async function loadHeaderAccount(signal: AbortSignal, fetcher: typeof fetch = fetch): Promise<HeaderAccount | null> {
  let response: Response;
  try {
    response = await fetcher("/auth/session", {
      credentials: "same-origin", cache: "no-store", signal, headers: { Accept: "application/json" },
    });
  } catch {
    if (signal.aborted) return null;
    throw new Error("Account status transport unavailable");
  }
  if (signal.aborted) return null;
  if (response.status === 401) return { kind: "expired" };
  if (response.status === 503) return { kind: "unavailable" };
  const body: unknown = await response.json();
  if (signal.aborted) return null;
  if (!body || typeof body !== "object" || !response.ok) throw new TypeError("Invalid account response");
  if ("kind" in body && body.kind === "anonymous") return { kind: "anonymous" };
  if ("kind" in body && body.kind === "authenticated" && "user" in body && body.user &&
      typeof body.user === "object" && "username" in body.user && typeof body.user.username === "string" &&
      "csrf" in body && typeof body.csrf === "string" && /^[A-Za-z0-9_-]{43}$/.test(body.csrf)) {
    // `staff` only decides whether to show the admin link; the admin area re-checks role and MFA.
    const staff = "staff" in body.user && body.user.staff === true;
    return { kind: "authenticated", username: body.user.username, csrf: body.csrf, ...(staff ? { staff: true as const } : {}) };
  }
  throw new TypeError("Invalid account response");
}
