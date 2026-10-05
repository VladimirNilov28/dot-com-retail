#!/usr/bin/env bash
# Reproducible local supergraph generation:
#   retail subgraph (Spring/DGS, running on the host) -> SDL -> rover compose -> supergraph.graphql
#
# Requires: Spring backend running on :8080, oauth-service running (docker compose, :4447),
# and Rover CLI installed (https://rover.apollo.dev/nix/latest).
set -euo pipefail
cd "$(dirname "$0")/.."

export SPRING_GRAPHQL_URL="${SPRING_GRAPHQL_URL:-http://127.0.0.1:8080/graphql}"
export OAUTH_TOKEN_URL="${OAUTH_TOKEN_URL:-http://127.0.0.1:4447/internal/token}"
export DEV_EMAIL="${DEV_EMAIL:-admin@bytecore.ee}"
export DEV_PASSWORD="${DEV_PASSWORD:-admin-dev-password}"

python3 - <<'PY'
import json
import os
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def post(url, body, label, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(url, data=json.dumps(body).encode(), headers=headers)
    try:
        with urlopen(request, timeout=30) as response:
            result = json.load(response)
    except HTTPError as error:
        if label == "Token issuance" and error.code == 403:
            raise SystemExit(
                "Token issuance rejected (HTTP 403). For TOTP, complete AAL2 "
                "login and export its access token as HIVE_BEARER_TOKEN."
            ) from None
        raise SystemExit(f"{label} failed (HTTP {error.code}).") from None
    except URLError:
        raise SystemExit(f"{label} endpoint is unavailable.") from None
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise SystemExit(f"{label} returned invalid JSON.") from None
    if not isinstance(result, dict):
        raise SystemExit(f"{label} returned an invalid response.")
    return result


token = os.environ.get("HIVE_BEARER_TOKEN")
if not token:
    print("==> Fetching a dev token from oauth-service")
    response = post(
        os.environ["OAUTH_TOKEN_URL"],
        {"email": os.environ["DEV_EMAIL"], "password": os.environ["DEV_PASSWORD"]},
        "Token issuance",
    )
    token = response.get("access_token")
    if not isinstance(token, str) or not token:
        raise SystemExit("Token issuance did not return an access token.")

print("==> Fetching authenticated retail subgraph SDL")
response = post(
    os.environ["SPRING_GRAPHQL_URL"],
    {"query": "{ _service { sdl } }"},
    "Subgraph SDL request",
    token,
)
data = response.get("data")
service = data.get("_service") if isinstance(data, dict) else None
sdl = service.get("sdl") if isinstance(service, dict) else None
if response.get("errors") or not isinstance(sdl, str) or not sdl.strip():
    raise SystemExit("Subgraph SDL request failed; check authentication and access scopes.")
Path("retail.graphql").write_text(
    "\n".join(line.rstrip() for line in sdl.splitlines()).rstrip() + "\n"
)
PY

echo "==> Composing local supergraph with Rover (Federation v2, no Hive Cloud / registry involved)"
TEMP_SUPERGRAPH=$(mktemp "./.supergraph.XXXXXX")
trap 'rm -f "$TEMP_SUPERGRAPH"' EXIT
rover supergraph compose --config ./supergraph-config.yaml --elv2-license accept > "$TEMP_SUPERGRAPH"
python3 - "$TEMP_SUPERGRAPH" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
path.write_text("\n".join(line.rstrip() for line in path.read_text().splitlines()).rstrip() + "\n")
PY
mv "$TEMP_SUPERGRAPH" supergraph.graphql

echo "==> Wrote $(pwd)/supergraph.graphql"
