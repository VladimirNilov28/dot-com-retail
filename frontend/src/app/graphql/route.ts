import "server-only";
import { handleGuestRequest, proxyFailure } from "@/lib/graphql/proxy";
import { hiveConfig } from "@/lib/graphql/server";
import { failure } from "@/lib/graphql/errors";

export async function POST(request: Request) {
  let config;
  try {
    config = hiveConfig(process.env);
  } catch (error) {
    if (!(error instanceof TypeError)) throw error;
    console.error("GraphQL configuration is missing or invalid.");
    return proxyFailure(failure("configuration", false), 503);
  }
  return handleGuestRequest(request, config);
}
