-- Staff-managed delivery options offered at checkout. Codes are the delivery kinds the
-- checkout engine understands (PICKUP has no shipping address). Placed orders keep their
-- own immutable shipping snapshot, so editing or deactivating a row never rewrites history.
CREATE TABLE shipping_options (
    id                 BIGSERIAL PRIMARY KEY,
    code               VARCHAR(20)  NOT NULL UNIQUE CHECK (code IN ('STANDARD', 'EXPRESS', 'PICKUP')),
    name               VARCHAR(100) NOT NULL CHECK (btrim(name) <> ''),
    description        VARCHAR(255),
    price              NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    currency           VARCHAR(3)   NOT NULL DEFAULT 'EUR' CHECK (currency ~ '^[A-Z]{3}$'),
    estimated_min_days INTEGER      NOT NULL CHECK (estimated_min_days BETWEEN 0 AND 365),
    estimated_max_days INTEGER      NOT NULL CHECK (estimated_max_days BETWEEN 0 AND 365),
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order         INTEGER      NOT NULL DEFAULT 0 CHECK (sort_order BETWEEN 0 AND 1000),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_shipping_options_estimate_order CHECK (estimated_min_days <= estimated_max_days)
);

-- Same codes, prices and estimate texts as the previous configuration fixtures.
INSERT INTO shipping_options (code, name, description, price, estimated_min_days, estimated_max_days, sort_order)
VALUES ('STANDARD', 'Standard delivery', '3-5 business days (development estimate)', 4.99, 3, 5, 10),
       ('EXPRESS', 'Express delivery', '1-2 business days (development estimate)', 9.99, 1, 2, 20),
       ('PICKUP', 'Pickup', '1 business day (simulated readiness)', 0.00, 1, 1, 30);
