CREATE TYPE order_status AS ENUM (
    'PENDING',
    'PAID',
    'SHIPPING',
    'COMPLETED',
    'CANCELLED'
);

CREATE TABLE orders (
    id bigserial PRIMARY KEY,
    public_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    user_id bigint NOT NULL,
    status order_status NOT NULL DEFAULT 'PENDING',
    total_amount decimal(10, 2) NOT NULL CHECK (total_amount >= 0),
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE order_items (
    id bigserial PRIMARY KEY,
    public_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    order_id bigint NOT NULL,
    product_variant_id bigint NOT NULL,
    -- FK added in V6__create_inventory.sql, once the `inventory` table exists.
    inventory_id bigint NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    price_at_purchase decimal(10, 2) NOT NULL CHECK (price_at_purchase >= 0),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_variant FOREIGN KEY (product_variant_id) REFERENCES product_variants (id)
);

CREATE TRIGGER trg_orders_updated_at
    BEFORE UPDATE ON orders
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at ();

