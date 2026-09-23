# oauth-service

Small Python service that bridges Ory Hydra and Ory Kratos, plus a dev-only
token endpoint. No framework, stdlib `http.server` only.

## What it does

- **Bootstrap** (`services/bootstrap.py`): on startup, reconciles Hydra OAuth2
  clients and seeds a dev admin Kratos identity from
  `infrastructure/oauth/access-control.yml`.
- **Login/consent bridge** (`api/bridge_server.py`, port `4446`): implements
  Hydra's `login`/`consent` challenge URLs (`/login`, `/consent`) for the
  browser-driven authorization-code flow, plus `/healthz`.
- **Internal dev token endpoint** (`api/internal_server.py`, port `4447`):
  `POST /internal/token` — takes `{"email": ..., "password": ...}`, drives
  the same Kratos + Hydra flow headlessly, and returns the real Hydra-issued
  JWT. Kratos still verifies the password and Hydra still signs the token —
  this service never does either itself.

## Layout

```
src/
  main.py               entrypoint — builds clients, runs bootstrap, starts both servers
  config.py              env-driven Config
  models.py               request/response dataclasses
  api/                     HTTP handlers (routing only)
  services/                 orchestration (bootstrap, login/consent, dev token)
  clients/                    HydraClient / KratosClient (all Hydra/Kratos HTTP calls)
```

## Local dev token

`/internal/token` is loopback-only (`compose.yml` publishes it as
`127.0.0.1:4447:4447` — not reachable from LAN or other containers):

```bash
curl -s -X POST http://127.0.0.1:4447/internal/token \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@bytecore.ee","password":"admin-dev-password"}'
```

Or with HTTPie:

```bash
http POST :4447/internal/token email=admin@bytecore.ee password=admin-dev-password
```

Returns `{"access_token": "...", "token_type": "bearer", ...}`.
