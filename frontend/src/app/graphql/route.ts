import "server-only";
import { handleGuestRequest, proxyFailure } from "@/lib/graphql/proxy";
import { hiveConfig } from "@/lib/graphql/server";
import { failure } from "@/lib/graphql/errors";
import { authConfig } from "@/lib/auth/config";
import { authCookie } from "@/lib/auth/model";
import { authenticatedState } from "@/lib/auth/session";

export async function POST(request: Request) {
  let config;
  try {
    config = hiveConfig(process.env);
  } catch (error) {
    if (!(error instanceof TypeError)) throw error;
    console.error("GraphQL configuration is missing or invalid.");
    return proxyFailure(failure("configuration", false), 503);
  }
  return handleGuestRequest(request, config, fetch, async () => {
    try {
      const id = authCookie(request.headers.get("cookie"), authConfig().sessionCookie);
      if (!id) return undefined;
      const { state, user } = await authenticatedState(id);
      return user.role === "USER" ? state.tokens?.accessToken : undefined;
    } catch {
      return undefined;
    }
  });
}
