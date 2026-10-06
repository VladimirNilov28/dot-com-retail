CREATE TABLE product_ratings (
    product_id bigint NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    user_id bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    stars smallint NOT NULL CHECK (stars BETWEEN 1 AND 5),
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    PRIMARY KEY (product_id, user_id)
);

CREATE INDEX idx_product_ratings_user ON product_ratings (user_id);

CREATE TRIGGER trg_product_ratings_updated_at
    BEFORE UPDATE ON product_ratings
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at ();
