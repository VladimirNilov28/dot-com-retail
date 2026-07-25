CREATE TABLE products (
    id bigserial PRIMARY KEY,
    name varchar(255) NOT NULL,
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
    stock_quantity integer NOT NULL DEFAULT 0 CHECK (stock_quantity >= 0),
    CONSTRAINT fk_product_variants_product FOREIGN KEY (product_id) REFERENCES products (id)
);

CREATE TRIGGER trg_products_updated_at
    BEFORE UPDATE ON products
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

