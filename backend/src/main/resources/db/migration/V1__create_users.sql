CREATE TYPE user_role AS ENUM (
    'ADMIN',
    'USER',
    'SUPPORT'
);

CREATE TABLE users (
    id bigserial PRIMARY KEY,
    ROLE user_role NOT NULL DEFAULT 'USER',
    username varchar(255) NOT NULL UNIQUE,
    email varchar(255) UNIQUE NOT NULL,
    password_hash varchar(255) NOT NULL,
    date_of_birth date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW()
);

CREATE TABLE user_address (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL,
    first_name varchar(50),
    last_name varchar(50),
    city varchar(100),
    country varchar(200),
    postal_code varchar(10),
    address_line1 varchar(255),
    address_line2 varchar(255),
    mobile varchar(20),
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_user_address_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE user_payment_methods (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL,
    provider varchar(255),
    -- TODO extend payment types
    type varchar(255),
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_user_payment_methods_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at ();

CREATE TRIGGER trg_user_address_updated_at
    BEFORE UPDATE ON user_address
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at ();

CREATE TRIGGER trg_user_payments_updated_at
    BEFORE UPDATE ON user_payment_methods
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at ();

