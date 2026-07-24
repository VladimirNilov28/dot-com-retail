CREATE TYPE payment_status AS ENUM (
    'SUCCESS',
    'FAIL',
    'PENDING'
);

CREATE TYPE order_status AS ENUM (
    'PENDING',
    'PAID',
    'SHIPPING',
    'COMPLETED'
);

CREATE TABLE orders (
    id bigserial NOT NULL PRIMARY KEY,
    user_id bigint NOT NULL UNIQUE,
    cart_id bigint NOT NULL UNIQUE,
    status order_status NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_order_cart FOREIGN KEY (cart_id) REFERENCES carts(id)
);

CREATE TABLE payment_details (
    id bigserial NOT NULL PRIMARY KEY,
    order_id bigserial NOT NULL UNIQUE,
    amount decimal(10, 2) NOT NULL,
    provider varchar(255) NOT NULL,
    status payment_status NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_
);

CREATE TRIGGER trg_user_updated_at