import logging

from clients.hydra import HydraClient
from clients.kratos import KratosClient
from config import Config

logger = logging.getLogger(__name__)


def perform_logout(
    config: Config,
    hydra_client: HydraClient,
    kratos_client: KratosClient,
    refresh_token: str,
    kratos_session_token: str | None = None,
) -> None:
    """Ends a user's ability to keep refreshing, and — where a Kratos native
    session is presented — ends that Kratos session too.

    Semantics (see docs/local-setup.md "Logout" for the full writeup):
      - Revoking the refresh token via Hydra is the hard guarantee: once
        this call returns, that refresh token can no longer be exchanged
        for a new access token. This step must succeed or the whole logout
        is considered failed.
      - Ending the Kratos session is best-effort/secondary: if the caller
        didn't have or didn't pass a Kratos session token (e.g. Kratos's
        session already expired, or the caller only ever had a Hydra
        token), we still consider the refresh-token revocation above to
        satisfy the minimum logout contract. A Kratos-side failure is
        logged, not raised, so a partially-stale Kratos session never masks
        the fact that the refresh capability was actually revoked.
      - This does NOT invalidate an already-issued, still-unexpired Hydra
        access-token JWT — Hydra's JWT access tokens are self-contained and
        not checked against a revocation list on every request. That JWT
        remains valid until its own (short, 30m) expiry regardless of
        logout; only its ability to be refreshed is removed here.
    """
    hydra_client.revoke_token(config.dev_client_id, refresh_token)

    if kratos_session_token:
        try:
            kratos_client.logout(kratos_session_token)
        except Exception as exc:  # noqa: BLE001 - best-effort secondary step, see docstring
            logger.warning("Kratos session logout failed (refresh token was already revoked): %s", exc)
