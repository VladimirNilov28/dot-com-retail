ALTER TABLE users ADD COLUMN kratos_identity_id uuid UNIQUE;

CREATE TABLE user_registration_reservations (
    id uuid PRIMARY KEY,
    flow_id uuid NOT NULL UNIQUE,
    username varchar(255) NOT NULL UNIQUE,
    email varchar(255) NOT NULL UNIQUE,
    date_of_birth date NOT NULL,
    expires_at timestamptz NOT NULL,
    kratos_identity_id uuid UNIQUE,
    created_at timestamptz NOT NULL DEFAULT NOW()
);

CREATE INDEX registration_reservation_expiry
    ON user_registration_reservations (expires_at)
    WHERE kratos_identity_id IS NULL;
