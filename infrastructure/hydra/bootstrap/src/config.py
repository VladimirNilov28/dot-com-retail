import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    hydra_admin_url: str
    access_control_file: str
    retry_attempts: int
    retry_delay_seconds: float


def load_config() -> Config:
    return Config(
        hydra_admin_url=os.environ.get("HYDRA_ADMIN_URL", "http://hydra:4445").rstrip("/"),
        access_control_file=os.environ.get("ACCESS_CONTROL_FILE", "/app/access-control.yml"),
        retry_attempts=int(os.environ.get("BOOTSTRAP_RETRY_ATTEMPTS", "10")),
        retry_delay_seconds=float(os.environ.get("BOOTSTRAP_RETRY_DELAY_SECONDS", "2")),
    )
