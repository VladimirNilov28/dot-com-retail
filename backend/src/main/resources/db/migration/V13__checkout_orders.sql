ALTER TABLE orders
    ALTER COLUMN user_id DROP NOT NULL,
    ADD COLUMN checkout_snapshot jsonb,
    ADD CONSTRAINT ck_guest_order_snapshot CHECK (user_id IS NOT NULL OR checkout_snapshot IS NOT NULL);

ALTER TABLE order_items ADD COLUMN checkout_snapshot jsonb;

CREATE TABLE checkout_requests (
    request_id uuid PRIMARY KEY,
    user_id bigint REFERENCES users(id),
    source_cart_id bigint NOT NULL,
    source_credential_hash varchar(64),
    guest_order_hash varchar(64),
    guest_expires_at timestamptz,
    payload_hash varchar(64) NOT NULL,
    order_id bigint NOT NULL UNIQUE REFERENCES orders(id),
    CONSTRAINT ck_checkout_request_owner CHECK (
        (user_id IS NOT NULL AND source_credential_hash IS NULL
            AND guest_order_hash IS NULL AND guest_expires_at IS NULL)
        OR (user_id IS NULL AND source_credential_hash IS NOT NULL
            AND guest_order_hash IS NOT NULL AND guest_expires_at IS NOT NULL)
    )
);
CREATE INDEX idx_checkout_guest_hash ON checkout_requests(guest_order_hash);

CREATE FUNCTION protect_checkout_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.checkout_snapshot IS NOT NULL THEN
        IF NEW.checkout_snapshot IS DISTINCT FROM OLD.checkout_snapshot THEN
            RAISE EXCEPTION 'Checkout snapshots are immutable';
        END IF;
        IF TG_TABLE_NAME = 'orders' THEN
            IF NEW.total_amount IS DISTINCT FROM OLD.total_amount OR NEW.user_id IS DISTINCT FROM OLD.user_id THEN
                RAISE EXCEPTION 'Checkout financial facts and ownership are immutable';
            END IF;
        ELSE
            IF NEW.quantity IS DISTINCT FROM OLD.quantity OR
               NEW.price_at_purchase IS DISTINCT FROM OLD.price_at_purchase OR
               NEW.inventory_id IS DISTINCT FROM OLD.inventory_id OR
               NEW.product_variant_id IS DISTINCT FROM OLD.product_variant_id OR
               NEW.order_id IS DISTINCT FROM OLD.order_id THEN
                RAISE EXCEPTION 'Checkout line facts are immutable';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_order_checkout_immutable BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION protect_checkout_snapshot();
CREATE TRIGGER trg_order_item_checkout_immutable BEFORE UPDATE ON order_items
    FOR EACH ROW EXECUTE FUNCTION protect_checkout_snapshot();
