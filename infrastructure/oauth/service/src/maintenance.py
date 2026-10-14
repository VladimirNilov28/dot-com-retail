"""Run bounded private registration maintenance using the service environment."""
import logging
import sys

from clients.hydra import HydraClient
from clients.spring_auth import SpringAuthClient
from config import load_config
from services.registration import RegistrationService


def main():
    try:
        config = load_config()
        hydra = HydraClient(config.hydra_admin_url, config.hydra_public_url)
        spring = SpringAuthClient(
            config.spring_internal_base_url,
            lambda: hydra.client_credentials_token(
                config.oauth_service_client_id, config.oauth_service_client_secret, "internal:provision-user",
            ),
        )
        result = RegistrationService(config, spring).maintain()
        logging.warning("Registration maintenance completed: %s", result)
        return 0
    except Exception as error:  # CLI boundary: no provider response or credentials in diagnostics.
        logging.error("Registration maintenance failed (%s); retry required.", type(error).__name__)
        return 1


if __name__ == "__main__":
    sys.exit(main())
