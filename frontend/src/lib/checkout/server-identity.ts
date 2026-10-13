import "server-only";
import { createHash, randomBytes } from "node:crypto";
import { authConfig } from "@/lib/auth/config";
import { authCookie } from "@/lib/auth/model";
import { authenticatedState } from "@/lib/auth/session";
import { guestCookies } from "@/lib/graphql/server-transport";
import type { CheckoutIdentity } from "./model";

export async function checkoutSession(request: Request, prepareGuest = false) {
  const config = authConfig();
  const id = authCookie(request.headers.get("cookie"), config.sessionCookie);
  const authenticated = id ? await authenticatedState(id) : null;
  if (authenticated?.user.role === "USER") return {
    owner: { mode: "user", scope: `user:${authenticated.user.id}` } satisfies CheckoutIdentity,
    cookie: undefined,
  };
  const cookies = guestCookies(request.headers.get("cookie"));
  const supplied = cookies.split("; ").find((part) => part.startsWith("retail_guest_orders="))?.split("=")[1];
  if (supplied && !/^[A-Za-z0-9_-]{43}$/.test(supplied)) throw new TypeError("Invalid guest order identity");
  const credential = supplied ?? (prepareGuest ? randomBytes(32).toString("base64url") : null);
  if (!credential) throw new TypeError("Guest checkout identity must be prepared");
  return {
    owner: { mode: "guest", scope: `guest:${createHash("sha256").update(credential).digest("hex")}` } satisfies CheckoutIdentity,
    cookie: !supplied ? `retail_guest_orders=${credential}; Path=/graphql; HttpOnly; SameSite=Lax; Max-Age=2592000${config.secure ? "; Secure" : ""}` : undefined,
  };
}
