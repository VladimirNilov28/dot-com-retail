import type { NextRequest } from "next/server";
import { handleSocialCallback } from "@/lib/auth/social";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

export function GET(request: NextRequest) {
  return handleSocialCallback(request);
}
