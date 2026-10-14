import { expect, test } from "bun:test";
import { authorizationUrl, newTransaction } from "./oauth";

test("storefront authorization cannot reuse a remembered Hydra subject from a logged-out account", async () => {
  const provider = Bun.serve({
    hostname: "127.0.0.1", port: 0,
    fetch() {
      return Response.json({
        issuer: provider.url.origin,
        authorization_endpoint: `${provider.url.origin}/oauth2/auth`,
        token_endpoint: `${provider.url.origin}/oauth2/token`,
        jwks_uri: `${provider.url.origin}/.well-known/jwks.json`,
        revocation_endpoint: `${provider.url.origin}/oauth2/revoke`,
        response_types_supported: ["code"], subject_types_supported: ["public"],
        id_token_signing_alg_values_supported: ["RS256"],
      });
    },
  });
  const database = new URL("postgresql://127.0.0.1:25432/fixture");
  database.username = "fixture";
  database.password = "fixture-password-not-issued";
  const environment = {
    STOREFRONT_ORIGIN: "http://127.0.0.1:3300", AUTH_OAUTH_ISSUER: provider.url.origin,
    AUTH_KRATOS_URL: "http://127.0.0.1:24433", AUTH_BRIDGE_URL: "http://127.0.0.1:24446",
    AUTH_CLIENT_ID: "fixture-client", AUTH_CLIENT_SECRET: "fixture-client-secret-not-issued-32",
    AUTH_BRIDGE_SECRET: "fixture-bridge-secret-not-issued-32", AUTH_DATABASE_URL: database.href,
    AUTH_SEAL_KEYS: JSON.stringify({ active: "fixture", keys: { fixture: Buffer.alloc(32, 1).toString("base64url") } }),
  };
  const previous = Object.fromEntries(Object.keys(environment).map(name => [name, process.env[name]]));
  Object.assign(process.env, environment);
  try {
    const transaction = newTransaction("/");
    const request = await authorizationUrl(transaction);
    expect(request.searchParams.get("prompt")).toBe("login");
    expect(request.searchParams.get("state")).toBe(transaction.state);
    expect(request.searchParams.get("nonce")).toBe(transaction.nonce);
    expect(request.searchParams.get("code_challenge_method")).toBe("S256");
    expect(request.searchParams.get("response_mode")).toBe("form_post");
    expect(request.searchParams.get("scope").split(" ").sort()).toEqual([
      "audit:read", "cart:read", "cart:write", "category:read", "category:write", "offline_access", "openid",
      "order:manage-status", "order:read", "order:write", "product:read", "product:write", "rating:write", "review:moderate",
      "shipping:manage", "user:manage-role", "user:read", "warehouse:read", "warehouse:write",
    ]);
  } finally {
    provider.stop(true);
    for (const name of Object.keys(environment)) {
      if (previous[name] === undefined) delete process.env[name];
      else process.env[name] = previous[name];
    }
  }
});
