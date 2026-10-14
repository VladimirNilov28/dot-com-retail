-- Account PII encryption at rest (application-level AES-256-GCM, keys from APP_DATA_KEYS).
-- Columns are converted in place: existing plaintext stays readable (converters pass legacy
-- plaintext through) until EncryptionBackfill rewrites each row with verified ciphertext and
-- fills email_index. Nothing is dropped, so a pre-migration backup plus the key ring is the
-- recovery path (docs/security/encryption-at-rest.md).
ALTER TABLE users
    ALTER COLUMN email TYPE varchar(1024),
    ALTER COLUMN date_of_birth TYPE varchar(1024) USING to_char(date_of_birth, 'YYYY-MM-DD'),
    ADD COLUMN email_index varchar(80);

-- Ciphertext is randomised, so uniqueness moves to the keyed index of the normalised
-- (trimmed, lower-cased) email. Legacy rows without an index are guarded by the
-- service-level lookup until the backfill indexes them.
ALTER TABLE users DROP CONSTRAINT users_email_key;
ALTER TABLE users ADD CONSTRAINT users_email_index_key UNIQUE (email_index);

ALTER TABLE user_registration_reservations
    ALTER COLUMN email TYPE varchar(1024),
    ALTER COLUMN date_of_birth TYPE varchar(1024) USING to_char(date_of_birth, 'YYYY-MM-DD'),
    ADD COLUMN email_index varchar(80);
ALTER TABLE user_registration_reservations DROP CONSTRAINT user_registration_reservations_email_key;
ALTER TABLE user_registration_reservations
    ADD CONSTRAINT user_registration_reservations_email_index_key UNIQUE (email_index);
