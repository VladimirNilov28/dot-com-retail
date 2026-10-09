import { NextRequest } from "next/server";
import { handleAuth } from "@/lib/auth/handlers";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: NextRequest, { params }: { params: Promise<{ action: string }> }) {
  return handleAuth(request, (await params).action);
}

export async function POST(request: NextRequest, { params }: { params: Promise<{ action: string }> }) {
  return handleAuth(request, (await params).action);
}
