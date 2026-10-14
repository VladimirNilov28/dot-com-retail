export function authenticationPath(path: string): boolean {
  return ["/login", "/register", "/verify", "/recover", "/reset"].includes(path) ||
    path === "/auth" || path.startsWith("/auth/") || path.startsWith("/self-service/");
}

export function abandonsAuthentication(request: Request): boolean {
  if (request.method !== "GET" || authenticationPath(new URL(request.url).pathname)) return false;
  if (request.headers.get("next-router-prefetch") || request.headers.get("next-router-segment-prefetch") ||
      [request.headers.get("purpose"), request.headers.get("sec-purpose")].some((value) => value?.includes("prefetch"))) return false;
  return request.headers.get("sec-fetch-dest") === "document" || request.headers.get("rsc") === "1";
}
