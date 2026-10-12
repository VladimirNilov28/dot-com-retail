-- Brands (first-class so they can later be faceted), canonical variant dimensions
-- in millimetres, and the product image metadata. Image bytes live in runtime
-- media storage outside the database and Git; only the reference is persisted.
CREATE TABLE brands (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL CHECK (btrim(name) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_brands_name_lower ON brands (lower(name));

ALTER TABLE products ADD COLUMN brand_id BIGINT REFERENCES brands (id) ON DELETE RESTRICT;
CREATE INDEX idx_products_brand_id ON products (brand_id);

ALTER TABLE product_variants
    ADD COLUMN length_mm INTEGER,
    ADD COLUMN width_mm  INTEGER,
    ADD COLUMN height_mm INTEGER,
    ADD CONSTRAINT ck_variant_dimensions_all_or_none CHECK (
        (length_mm IS NULL AND width_mm IS NULL AND height_mm IS NULL)
        OR (length_mm IS NOT NULL AND width_mm IS NOT NULL AND height_mm IS NOT NULL)),
    ADD CONSTRAINT ck_variant_dimensions_range CHECK (
        (length_mm IS NULL OR length_mm BETWEEN 1 AND 100000)
        AND (width_mm IS NULL OR width_mm BETWEEN 1 AND 100000)
        AND (height_mm IS NULL OR height_mm BETWEEN 1 AND 100000));

CREATE TABLE product_images (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    storage_key VARCHAR(80) NOT NULL UNIQUE,
    content_type VARCHAR(40) NOT NULL,
    byte_size   INTEGER NOT NULL CHECK (byte_size > 0),
    width       INTEGER NOT NULL CHECK (width > 0),
    height      INTEGER NOT NULL CHECK (height > 0),
    alt_text    VARCHAR(200) NOT NULL CHECK (btrim(alt_text) <> ''),
    position    INTEGER NOT NULL CHECK (position >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_product_images_product_position ON product_images (product_id, position);
