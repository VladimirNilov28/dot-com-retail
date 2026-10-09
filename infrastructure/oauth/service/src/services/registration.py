import hashlib
import json
import secrets
from datetime import datetime, timedelta, timezone
from uuid import UUID

import psycopg
from altcha import Payload, create_challenge, verify_solution
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb


class RegistrationRejected(ValueError):
    pass


class CaptchaUnavailable(RuntimeError):
    pass


class RegistrationService:
    def __init__(self, config, spring_client):
        self.config = config
        self.spring_client = spring_client

    def _connection(self):
        if (not self.config.auth_database_url or len(self.config.captcha_secret) < 32
                or self.config.captcha_secret.startswith("replace-with")):
            raise CaptchaUnavailable("Registration security is not configured.")
        return psycopg.connect(
            self.config.auth_database_url, connect_timeout=5,
            options="-c statement_timeout=10000", row_factory=dict_row,
        )

    def challenge(self, flow_id: str) -> dict:
        flow_id = str(UUID(flow_id))
        now = datetime.now(timezone.utc)
        challenge = create_challenge(
            "PBKDF2/SHA-256", 5000, counter=secrets.randbelow(500) + 500,
            expires_at=now + timedelta(minutes=5),
            data={"flow_id": flow_id, "purpose": "registration"},
            hmac_secret=self.config.captcha_secret,
        ).to_dict()
        with self._connection() as connection:
            connection.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))", (flow_id,))
            count = connection.execute(
                "SELECT count(*) AS count FROM captcha_challenges WHERE flow_id=%s "
                "AND created_at > now() - interval '1 minute'", (flow_id,),
            ).fetchone()["count"]
            if count >= 10:
                raise RegistrationRejected("Too many security checks. Wait a minute and try again.")
            connection.execute(
                "INSERT INTO captcha_challenges(nonce,flow_id,challenge,expires_at) VALUES(%s,%s,%s,%s)",
                (challenge["parameters"]["nonce"], flow_id, Jsonb(challenge), now + timedelta(minutes=5)),
            )
        return challenge

    def maintain(self) -> dict:
        with self._connection() as connection:
            deleted = connection.execute(
                "WITH expired AS (SELECT nonce FROM captcha_challenges "
                "WHERE expires_at < now() - interval '1 hour' ORDER BY expires_at "
                "LIMIT 500 FOR UPDATE SKIP LOCKED) "
                "DELETE FROM captcha_challenges USING expired "
                "WHERE captcha_challenges.nonce=expired.nonce RETURNING captcha_challenges.nonce",
            ).fetchall()
        result = self.spring_client.maintain_registration()
        return {"captcha_deleted": len(deleted), "reservations_released": result["released"]}

    def pre_persist(self, body: dict, trigger_id: str) -> dict:
        if body.get("schema_id") != "customer-v1" or body.get("flow_type") != "browser":
            raise RegistrationRejected("Use storefront registration.")
        flow_id = UUID(body["flow_id"])
        trigger_id = UUID(trigger_id)
        traits = body.get("traits")
        if not isinstance(traits, dict) or set(traits) != {"username", "email", "dateOfBirth"}:
            raise RegistrationRejected("Complete the required account fields.")
        if any(not isinstance(value, str) or not value.strip() or len(value) > 255 for value in traits.values()):
            raise RegistrationRejected("Complete the required account fields.")
        payload_text = body.get("altcha")
        if not isinstance(payload_text, str) or len(payload_text) > 32768:
            raise RegistrationRejected("Complete a fresh security check.")
        try:
            payload = Payload.from_base64(payload_text)
            challenge = payload.challenge.to_dict()
            nonce = payload.challenge.parameters.nonce
        except (ValueError, TypeError, KeyError) as error:
            raise RegistrationRejected("The security check is invalid. Try again.") from error
        if not isinstance(nonce, str) or len(nonce) > 256:
            raise RegistrationRejected("The security check is invalid. Try again.")
        digest = hashlib.sha256(json.dumps(body, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        with self._connection() as connection:
            row = connection.execute(
                "SELECT * FROM captcha_challenges WHERE nonce=%s FOR UPDATE", (nonce,),
            ).fetchone()
            if row is None or row["flow_id"] != flow_id or row["challenge"] != challenge:
                raise RegistrationRejected("The security check does not belong to this registration.")
            if row["expires_at"] <= datetime.now(timezone.utc):
                raise RegistrationRejected("The security check expired. Try again.")
            if row["used_trigger"] is not None:
                if row["used_trigger"] != trigger_id or row["payload_digest"] != digest:
                    raise RegistrationRejected("This security check was already used. Try again.")
                if row["reservation"] is not None:
                    return self._metadata(row["reservation"])
            else:
                result = verify_solution(payload, self.config.captcha_secret)
                if not result.verified:
                    raise RegistrationRejected("The security check is invalid or expired. Try again.")
                connection.execute(
                    "UPDATE captcha_challenges SET used_trigger=%s,payload_digest=%s WHERE nonce=%s",
                    (trigger_id, digest, nonce),
                )
        # Commit consumption first. An interrupted authenticated delivery may
        # retry idempotently; a different browser submission cannot reuse proof.
        reservation = self.spring_client.reserve_registration(str(flow_id), traits, body["expires_at"])
        with self._connection() as connection:
            connection.execute(
                "UPDATE captcha_challenges SET reservation=%s WHERE nonce=%s "
                "AND used_trigger=%s AND payload_digest=%s",
                (Jsonb(reservation), nonce, trigger_id, digest),
            )
        return self._metadata(reservation)

    @staticmethod
    def _metadata(reservation: dict) -> dict:
        reference = str(UUID(reservation["id"]))
        return {"identity": {"metadata_admin": {
            "bytecore_registration": {"reservation_id": reference},
        }}}
