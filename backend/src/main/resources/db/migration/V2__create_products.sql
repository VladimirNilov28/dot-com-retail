CREATE TABLE products (
    id bigserial PRIMARY KEY,
    name varchar(255) NOT NULL,
    slug varchar(255) NOT NULL UNIQUE,
    description text,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW()
);

CREATE TABLE product_variants (
    id bigserial PRIMARY KEY,
    product_id bigint NOT NULL,
    sku varchar(255) NOT NULL UNIQUE,
    -- TODO Add some additional information for products like: model number, color, type and etc.
    price DECIMAL(10, 2) NOT NULL CHECK (price >= 0),
    attributes jsonb NOT NULL DEFAULT '{}' ::jsonb,
    barcode varchar(64),
    weight_grams integer CHECK (weight_grams >= 0),
    is_active boolean NOT NULL DEFAULT TRUE,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_product_variants_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE
);

CREATE INDEX idx_product_variants_attributes ON product_variants USING gin (attributes);

CREATE TRIGGER trg_products_updated_at
    BEFORE UPDATE ON products
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at ();

CREATE TRIGGER trg_product_variants_updated_at
    BEFORE UPDATE ON product_variants
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at ();

