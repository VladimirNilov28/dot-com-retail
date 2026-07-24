CREATE TABLE products (
    id bigserial NOT NULL PRIMARY KEY,
    name varchar(255) NOT NULL,
    description text,
    created_at timestamptz NOT NULL DEFAULT NOW()
);

CREATE TABLE product_variants (
    id bigserial NOT NULL PRIMARY KEY,
    product_id bigint NOT NULL,
    sku varchar(255) NOT NULL UNIQUE,
    -- TODO Add some additional information for products like: model number, color, type and etc.
    price DECIMAL(10, 2) NOT NULL,
    stock_quantity integer NOT NULL DEFAULT 0,
    CONSTRAINT fk_product_variants_product FOREIGN KEY (product_id) REFERENCES products (id)
);

