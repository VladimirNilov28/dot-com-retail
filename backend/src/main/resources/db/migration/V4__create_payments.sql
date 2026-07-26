CREATE TYPE payment_status AS ENUM (
    'SUCCESS',
    'FAILED',
    'PENDING'
);

CREATE TYPE order_status AS ENUM (
    'PENDING',
    'PAID',
    'SHIPPING',
    'COMPLETED',
    'CANCELLED'
);

CREATE TABLE orders (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL,
    cart_id bigint,
    status order_status NOT NULL DEFAULT 'PENDING',
    total_amount decimal(10, 2) NOT NULL CHECK (total_amount >= 0),
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_order_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE SET NULL
);

CREATE TABLE order_items (
    id bigserial PRIMARY KEY,
    order_id bigint NOT NULL,
    product_variant_id bigint NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    price_at_purchase decimal(10, 2) NOT NULL CHECK (price_at_purchase >= 0),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_variant FOREIGN KEY (product_variant_id) REFERENCES product_variants (id)
);

CREATE TABLE payment_details (
    id bigserial PRIMARY KEY,
    order_id bigint NOT NULL,
    amount decimal(10, 2) NOT NULL CHECK (amount >= 0),
    provider varchar(255) NOT NULL,
    -- TODO extend payment types
    type varchar(255) NOT NULL,
    status payment_status NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE TRIGGER trg_orders_updated_at
    BEFORE UPDATE ON orders
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at ();

CREATE TRIGGER trg_payment_details_updated_at
    BEFORE UPDATE ON payment_details
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at ();

