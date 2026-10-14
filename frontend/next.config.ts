import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Opt-in self-contained server bundle for the review container (frontend/Dockerfile).
  ...(process.env.NEXT_OUTPUT === "standalone" ? { output: "standalone" as const } : {}),
  allowedDevOrigins: ["127.0.0.1"],
  reactCompiler: true,
  logging: {
    incomingRequests: { ignore: [/^\/auth(?:\/|$)/, /^\/self-service\/methods\/oidc\/callback(?:\/|$)/] },
  },
  async headers() {
    return ["/auth/:path*", "/login", "/register", "/verify", "/account", "/admin/:path*"].map((source) => ({
      source,
      headers: [
        { key: "Cache-Control", value: "no-store, private" },
        { key: "X-Robots-Tag", value: "noindex, nofollow" },
        { key: "Referrer-Policy", value: "same-origin" },
        { key: "X-Content-Type-Options", value: "nosniff" },
        { key: "X-Frame-Options", value: "DENY" },
      ],
    }));
  },
};

export default nextConfig;
