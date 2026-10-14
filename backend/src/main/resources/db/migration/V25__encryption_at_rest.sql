-- Encryption at rest (application-level AES-256-GCM, keys supplied via APP_DATA_KEYS).
-- Ciphertext is longer than the plaintext limits, which remain enforced by bean validation.
ALTER TABLE user_address
    ALTER COLUMN first_name TYPE varchar(1024),
    ALTER COLUMN last_name TYPE varchar(1024),
    ALTER COLUMN city TYPE varchar(1024),
    ALTER COLUMN country TYPE varchar(1024),
    ALTER COLUMN postal_code TYPE varchar(1024),
    ALTER COLUMN address_line1 TYPE varchar(1024),
    ALTER COLUMN address_line2 TYPE varchar(1024),
    ALTER COLUMN mobile TYPE varchar(1024);

ALTER TABLE order_notifications ALTER COLUMN recipient TYPE varchar(1024);

-- Checkout snapshots stay immutable, except for the one-way legacy-plaintext -> sealed envelope
-- transition and re-sealing under a different key id during key rotation.
CREATE OR REPLACE FUNCTION protect_checkout_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.checkout_snapshot IS NOT NULL THEN
        IF NEW.checkout_snapshot IS DISTINCT FROM OLD.checkout_snapshot THEN
            IF NOT COALESCE(TG_TABLE_NAME = 'orders'
                    AND jsonb_typeof(NEW.checkout_snapshot -> 'sealed') = 'string'
                    AND (NOT jsonb_exists(OLD.checkout_snapshot, 'sealed')
                         OR split_part(OLD.checkout_snapshot ->> 'sealed', ':', 2)
                            <> split_part(NEW.checkout_snapshot ->> 'sealed', ':', 2)), false) THEN
                RAISE EXCEPTION 'Checkout snapshots are immutable';
            END IF;
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

CREATE INDEX idx_orders_contact_email_index ON orders ((checkout_snapshot ->> 'contactEmailIndex'));
