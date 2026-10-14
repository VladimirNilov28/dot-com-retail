-- Written reviews, helpful votes and post-moderation, extending the V15 rating model.
-- One row per (product, user) remains the single source of truth for both the star
-- rating and the optional written review, so ratings and reviews can never diverge.
-- Legacy star-only rows stay valid: title/body are NULL together (no written review).

ALTER TABLE product_ratings
    ADD COLUMN id bigint GENERATED ALWAYS AS IDENTITY,
    ADD COLUMN title varchar(120),
    ADD COLUMN body text,
    ADD COLUMN status varchar(16) NOT NULL DEFAULT 'VISIBLE',
    ADD COLUMN moderated_by bigint REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN moderated_at timestamptz,
    ADD COLUMN moderation_reason varchar(500);

ALTER TABLE product_ratings
    ADD CONSTRAINT uq_product_ratings_id UNIQUE (id),
    ADD CONSTRAINT ck_product_ratings_status CHECK (status IN ('VISIBLE', 'HIDDEN')),
    ADD CONSTRAINT ck_product_ratings_text_pair CHECK ((title IS NULL) = (body IS NULL)),
    ADD CONSTRAINT ck_product_ratings_title CHECK (
        title IS NULL OR (char_length(title) BETWEEN 1 AND 120 AND title = btrim(title))),
    ADD CONSTRAINT ck_product_ratings_body CHECK (
        body IS NULL OR (char_length(body) BETWEEN 10 AND 4000 AND body = btrim(body))),
    ADD CONSTRAINT ck_product_ratings_hidden_reason CHECK (
        status = 'VISIBLE' OR (moderation_reason IS NOT NULL AND moderated_at IS NOT NULL)),
    ADD CONSTRAINT ck_product_ratings_reason_length CHECK (
        moderation_reason IS NULL OR char_length(moderation_reason) BETWEEN 3 AND 500);

-- Public listing/aggregates filter by product and visibility; moderation lists by status.
CREATE INDEX idx_product_ratings_product_status ON product_ratings (product_id, status, created_at DESC);
CREATE INDEX idx_product_ratings_status_created ON product_ratings (status, created_at DESC);

-- Helpful votes: the primary key makes each user's vote unique and concurrent votes idempotent.
-- The helpful count is always computed from this table (no denormalized counter to drift).
CREATE TABLE review_votes (
    review_id bigint NOT NULL REFERENCES product_ratings (id) ON DELETE CASCADE,
    user_id bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    PRIMARY KEY (review_id, user_id)
);

CREATE INDEX idx_review_votes_user ON review_votes (user_id);
