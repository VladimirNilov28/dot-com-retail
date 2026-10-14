ALTER TABLE carts
    ALTER COLUMN user_id DROP NOT NULL,
    ADD COLUMN guest_credential_hash varchar(64),
    ADD COLUMN guest_expires_at timestamptz,
    ADD CONSTRAINT ck_cart_owner CHECK (
        (user_id IS NOT NULL AND guest_credential_hash IS NULL AND guest_expires_at IS NULL)
        OR (user_id IS NULL AND guest_credential_hash IS NOT NULL AND guest_expires_at IS NOT NULL)
    ),
    ADD CONSTRAINT uq_guest_cart_credential UNIQUE (guest_credential_hash);

CREATE INDEX idx_guest_cart_expiry ON carts (guest_expires_at) WHERE user_id IS NULL;

CREATE TABLE guest_cart_merge_receipts (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id uuid NOT NULL,
    source_cart_id bigint NOT NULL,
    source_credential_hash varchar(64) NOT NULL,
    merged_items jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT uq_guest_merge_request UNIQUE (user_id, request_id),
    CONSTRAINT uq_guest_merge_source UNIQUE (source_cart_id),
    CONSTRAINT uq_guest_merge_credential UNIQUE (source_credential_hash)
);

CREATE INDEX idx_guest_merge_expiry ON guest_cart_merge_receipts(expires_at);
