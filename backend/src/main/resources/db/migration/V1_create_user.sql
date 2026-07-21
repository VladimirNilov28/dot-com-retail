CREATE TABLE users (
    id bigserial NOT NULL PRIMARY KEY,
    role VARCHAR(20) NOT NULL CHECK (ROLE IN ('admin', 'user')) DEFAULT 'user',
    username varchar(255) NOT NULL UNIQUE,
    email varchar(255) UNIQUE NOT NULL,
    password_hash varchar(255) NOT NULL,
    date_of_birth date NOT NULL,
    created_at TIMESTAMPZ NOT NULL DEFAULT NOW()
);

CREATE TABLE user_address (
    id bigserial NOT NULL PRIMARY KEY,
    user_id BIGINTEGER NOT NULL,
    first_name varchar(50),
    last_name varchar(50),
    city varchar(100),
    country varchar(100),
    postal_code varchar(100),
    address_line1 varchar(255),
    address_line2 varchar(255),
    mobile integer,
    CONSTRAINT fk_users_user_address FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE user_payment ();

