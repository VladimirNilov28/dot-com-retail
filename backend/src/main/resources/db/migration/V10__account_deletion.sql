-- Keep a non-authenticatable, anonymized owner for historical order/event FKs.
-- The identity id is a retry coordinate, never a credential or session token.
ALTER TABLE users
    ADD COLUMN deletion_identity_id uuid,
    ADD COLUMN deleted boolean NOT NULL DEFAULT false;
