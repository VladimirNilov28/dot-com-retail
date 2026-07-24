CREATE TABLE carts (
    id bigserial NOT NULL PRIMARY KEY,
    user_id bigint NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_cart_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE cart_items (
    id bigserial NOT NULL PRIMARY KEY,
    cart_id bigint NOT NULL,
    product_variant_id bigint NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_items_product FOREIGN KEY (product_variant_id) REFERENCES product_variants (id),
    CONSTRAINT uq_cart_item_variant UNIQUE (cart_id, product_variant_id)
);

CREATE TRIGGER trg_carts_updated_at
    BEFORE UPDATE ON carts
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

