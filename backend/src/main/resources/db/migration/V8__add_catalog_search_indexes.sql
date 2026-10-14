-- Catalog discovery (search/suggestions) uses ILIKE '%term%' matching on
-- Product.name/description and ProductVariant.sku. Plain btree indexes can't
-- accelerate that access pattern, so pg_trgm trigram GIN indexes are added
-- instead. product_variants.attributes already has a GIN index (V2), reused
-- as-is for JSONB attribute-filter containment queries.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_products_name_trgm ON products USING gin (name gin_trgm_ops);

CREATE INDEX idx_products_description_trgm ON products USING gin (description gin_trgm_ops);

CREATE INDEX idx_product_variants_sku_trgm ON product_variants USING gin (sku gin_trgm_ops);
