import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  allowedDevOrigins: ["127.0.0.1"],
  reactCompiler: true,
  // Bulk product import posts files of up to 1 MB through a server action (multipart overhead included).
  experimental: { serverActions: { bodySizeLimit: "2mb" } },
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
