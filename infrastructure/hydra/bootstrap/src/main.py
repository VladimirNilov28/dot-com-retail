import logging
import sys

from config import load_config
from hydra_client import HydraNotReadyError, wait_until_ready
from models import AccessControlSpecError, load_and_validate
from reconciler import reconcile

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
logger = logging.getLogger(__name__)


def main() -> int:
    config = load_config()

    try:
        wait_until_ready(config.hydra_admin_url, config.retry_attempts, config.retry_delay_seconds)
    except HydraNotReadyError as exc:
        logger.error("%s", exc)
        return 1

    try:
        spec = load_and_validate(config.access_control_file)
    except AccessControlSpecError as exc:
        logger.error("invalid access-control spec: %s", exc)
        return 1

    try:
        reconcile(config.hydra_admin_url, spec)
    except Exception as exc:  # noqa: BLE001 - top-level failure boundary for a one-shot job
        logger.error("reconciliation failed: %s", exc)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
