import logging
import sys
import threading
from http.server import ThreadingHTTPServer

from api.bridge_server import make_bridge_handler
from api.internal_server import make_internal_handler
from clients.hydra import HydraClient, HydraNotReadyError
from clients.kratos import KratosClient, KratosNotReadyError
from config import ConfigError, load_config
from services import bootstrap
from services.bootstrap import AccessControlSpecError

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
logger = logging.getLogger(__name__)


def main() -> int:
    try:
        config = load_config()
    except ConfigError as exc:
        logger.error("%s", exc)
        return 1

    hydra_client = HydraClient(config.hydra_admin_url, config.hydra_public_url)
    kratos_client = KratosClient(config.kratos_admin_url, config.kratos_public_url)

    try:
        bootstrap.run(config, hydra_client, kratos_client)
    except (HydraNotReadyError, KratosNotReadyError, AccessControlSpecError) as exc:
        logger.error("bootstrap failed: %s", exc)
        return 1

    internal_server = ThreadingHTTPServer(
        (config.internal_token_host, config.internal_token_port),
        make_internal_handler(config, hydra_client, kratos_client),
    )
    threading.Thread(target=internal_server.serve_forever, daemon=True).start()
    logger.info(
        "serving internal dev-token endpoint on %s:%s",
        config.internal_token_host,
        config.internal_token_port,
    )

    bridge_server = ThreadingHTTPServer(
        (config.bridge_host, config.bridge_port),
        make_bridge_handler(config, hydra_client, kratos_client),
    )
    logger.info("serving login/consent bridge on %s:%s", config.bridge_host, config.bridge_port)
    bridge_server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
