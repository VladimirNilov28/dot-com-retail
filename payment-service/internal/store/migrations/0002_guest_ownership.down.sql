-- Refuse rollback while genuine guest payments exist; do not invent user IDs.
ALTER TABLE payments ALTER COLUMN user_id SET NOT NULL;
