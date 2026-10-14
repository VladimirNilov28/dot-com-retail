-- Public contact-form submissions and their retryable email outbox.
CREATE TABLE contact_messages (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL,
    subject VARCHAR(30) NOT NULL
        CHECK (subject IN ('ORDER', 'PAYMENT', 'PRODUCT', 'ACCOUNT', 'FEEDBACK', 'OTHER')),
    message TEXT NOT NULL CHECK (char_length(message) BETWEEN 10 AND 4000),
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX contact_messages_created ON contact_messages(created_at DESC);

-- Same delivery contract as order_notifications: PENDING -> SENT, or DEAD after
-- bounded retries with exponential backoff.
CREATE TABLE contact_notifications (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES contact_messages(id) ON DELETE CASCADE,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('SUPPORT', 'ACKNOWLEDGEMENT')),
    recipient VARCHAR(254) NOT NULL,
    reply_to VARCHAR(254),
    subject TEXT NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (message_id, kind)
);
CREATE INDEX contact_notifications_pending ON contact_notifications(next_attempt_at)
    WHERE status = 'PENDING';
