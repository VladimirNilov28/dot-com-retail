import "server-only";
import * as oidc from "openid-client";
import { authConfig } from "./config";
import { AuthError, type OAuthTransaction } from "./model";

let discovery: Promise<oidc.Configuration> | undefined;

export async function oauthClient(): Promise<oidc.Configuration> {
  if (!discovery) {
    const config = authConfig();
    discovery = oidc.discovery(
      new URL(config.issuer), config.clientId,
      { token_endpoint_auth_method: "client_secret_basic", id_token_signed_response_alg: "RS256" },
      oidc.ClientSecretBasic(config.clientSecret),
      { timeout: 8, execute: config.secure ? [oidc.enableNonRepudiationChecks] :
        [oidc.allowInsecureRequests, oidc.enableNonRepudiationChecks] },
    ).then((client) => {
      const metadata = client.serverMetadata();
      for (const endpoint of [metadata.authorization_endpoint, metadata.token_endpoint, metadata.jwks_uri,
        metadata.revocation_endpoint]) {
        if (!endpoint || new URL(endpoint).origin !== config.issuer) throw new AuthError("unavailable");
      }
      return client;
    }).catch(() => {
      discovery = undefined;
      throw new AuthError("unavailable");
    });
  }
  return discovery;
}

export function newTransaction(returnTo: string): OAuthTransaction {
  return { state: oidc.randomState(), nonce: oidc.randomNonce(), verifier: oidc.randomPKCECodeVerifier(), returnTo };
}

export async function authorizationUrl(transaction: OAuthTransaction): Promise<URL> {
  const config = authConfig();
  return oidc.buildAuthorizationUrl(await oauthClient(), {
    redirect_uri: `${config.origin}/auth/callback`, response_type: "code", response_mode: "form_post",
    scope: "openid offline_access user:read", state: transaction.state, nonce: transaction.nonce,
    code_challenge: await oidc.calculatePKCECodeChallenge(transaction.verifier), code_challenge_method: "S256",
  });
}

export async function exchangeCode(request: Request, transaction: OAuthTransaction) {
  try {
    return await oidc.authorizationCodeGrant(await oauthClient(), request, {
      expectedState: transaction.state, expectedNonce: transaction.nonce,
      pkceCodeVerifier: transaction.verifier, idTokenExpected: true,
    });
  } catch (error) {
    if (error instanceof AuthError) throw error;
    if (error instanceof oidc.AuthorizationResponseError ||
        (error instanceof oidc.ResponseBodyError && error.error === "invalid_grant") ||
        (error instanceof oidc.ClientError && [
          "OAUTH_INVALID_RESPONSE", "OAUTH_JWT_CLAIM_COMPARISON_FAILED", "OAUTH_JWT_TIMESTAMP_CHECK_FAILED",
        ].includes(error.code ?? ""))) throw new AuthError("invalid_request", 400);
    throw new AuthError("unavailable");
  }
}

export async function refreshGrant(refreshToken: string) {
  try {
    return await oidc.refreshTokenGrant(await oauthClient(), refreshToken);
  } catch (error) {
    if (error instanceof AuthError) throw error;
    if (error instanceof oidc.ResponseBodyError && error.error === "invalid_grant") {
      throw new AuthError("reauthentication_required", 401);
    }
    throw new AuthError("unavailable");
  }
}

export async function revokeGrant(refreshToken: string): Promise<void> {
  try {
    await oidc.tokenRevocation(await oauthClient(), refreshToken, { token_type_hint: "refresh_token" });
  } catch {
    throw new AuthError("unavailable");
  }
}
