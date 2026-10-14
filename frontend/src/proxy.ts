import { NextRequest, NextResponse } from "next/server";
import { authConfig } from "@/lib/auth/config";
import { AuthError, authCookie } from "@/lib/auth/model";
import { cancelLoginAttempt } from "@/lib/auth/attempt";
import { abandonsAuthentication } from "@/lib/auth/navigation";

export async function proxy(request: NextRequest) {
  if (!abandonsAuthentication(request)) return NextResponse.next();
  if (!(request.headers.get("cookie") ?? "").split(";").some((part) =>
    ["bc_auth", "__Host-bc_auth"].includes(part.split("=", 1)[0].trim()))) return NextResponse.next();
  try {
    const browser = authCookie(request.headers.get("cookie"), authConfig().flowCookie);
    if (browser) await cancelLoginAttempt(browser);
    return NextResponse.next();
  } catch (error) {
    if (error instanceof AuthError && error.code === "expired") return NextResponse.next();
    console.error("Authentication abandonment could not be committed");
    return NextResponse.json({ error: "authentication_cancellation_unavailable" }, { status: 503,
      headers: { "Cache-Control": "private, no-store" } });
  }
}

export const config = {
  matcher: "/((?!_next|api|graphql|favicon.ico|robots.txt|sitemap.xml|.*\\.(?:png|jpg|jpeg|svg|webp|woff2|css|js)$).*)",
};
