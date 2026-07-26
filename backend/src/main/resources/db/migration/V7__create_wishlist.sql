CREATE TABLE wishlists (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_wishlist_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE wishlist_items (
    id bigserial PRIMARY KEY,
    wishlist_id bigint NOT NULL,
    product_variant_id bigint NOT NULL,
    added_at timestamptz NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_wishlist_items_wishlist FOREIGN KEY (wishlist_id) REFERENCES wishlists (id) ON DELETE CASCADE,
    CONSTRAINT fk_wishlist_items_variant FOREIGN KEY (product_variant_id) REFERENCES product_variants (id) ON DELETE CASCADE,
    CONSTRAINT uq_wishlist_item_variant UNIQUE (wishlist_id, product_variant_id)
);

