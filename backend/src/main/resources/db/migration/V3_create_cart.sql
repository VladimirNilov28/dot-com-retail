CREATE TABLE carts (
    id bigserial NOT NULL PRIMARY KEY,
    user_id bigint NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_cart_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE cart_items (
    id bigserial NOT NULL PRIMARY KEY,
    cart_id bigint NOT NULL,
    product_variant_id bigint NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0 AND quantity < 100),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_items_product FOREIGN KEY (product_variant_id) REFERENCES product_variants (id)
);

