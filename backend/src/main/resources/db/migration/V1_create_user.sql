CREATE TYPE user_role AS ENUM (
    'admin',
    'user',
    'support'
);

CREATE TABLE users (
    id bigserial NOT NULL PRIMARY KEY,
    ROLE user_role NOT NULL DEFAULT 'user',
    username varchar(255) NOT NULL UNIQUE,
    email varchar(255) UNIQUE NOT NULL,
    password_hash varchar(255) NOT NULL,
    date_of_birth date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT NOW()
);

CREATE TABLE user_address (
    id bigserial NOT NULL PRIMARY KEY,
    user_id bigint NOT NULL UNIQUE,
    first_name varchar(50),
    last_name varchar(50),
    city varchar(100),
    country varchar(200),
    postal_code varchar(10),
    address_line1 varchar(255),
    address_line2 varchar(255),
    mobile varchar(20),
    CONSTRAINT fk_user_address_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE user_payment (
    id bigserial NOT NULL PRIMARY KEY,
    user_id bigint NOT NULL,
    -- TODO Finish payment table with other fields (Note: mb it is good idea to move payment to different domain like V*_create_payments_tabels.sql)
    CONSTRAINT fk_user_payment_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

