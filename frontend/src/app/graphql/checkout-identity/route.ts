import { authConfig } from "@/lib/auth/config";
import { checkoutSession } from "@/lib/checkout/server-identity";
import { AuthError } from "@/lib/auth/model";

export async function POST(request: Request) {
  if (request.headers.get("Origin") !== authConfig().origin || request.headers.get("X-Guest-Cart-Request") !== "1" ||
      request.headers.has("Authorization")) return Response.json({ error: "forbidden" }, { status: 403 });
  try {
    const session = await checkoutSession(request, true);
    const headers = new Headers({ "Cache-Control": "private, no-store" });
    if (session.cookie) headers.append("Set-Cookie", session.cookie);
    return Response.json(session.owner, { headers });
  } catch (error) {
    console.error("Checkout identity verification failed");
    const status = error instanceof AuthError && ["expired", "reauthentication_required"].includes(error.code) ? 401 : 503;
    return Response.json({ error: "identity_unavailable" }, { status, headers: { "Cache-Control": "private, no-store" } });
  }
}
