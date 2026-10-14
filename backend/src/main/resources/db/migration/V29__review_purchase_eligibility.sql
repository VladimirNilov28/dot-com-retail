-- Reviews require a qualifying purchase (see docs/api/reviews.md). The eligibility and
-- "verified purchase" checks walk orders(user_id) -> order_items(order_id) ->
-- product_variants(product_id); orders(user_id) is already indexed (V19), these cover the rest.
-- Helpful sorting counts review_votes by review_id, which its primary key already covers.
CREATE INDEX IF NOT EXISTS idx_order_items_order_variant ON order_items (order_id, product_variant_id);
CREATE INDEX IF NOT EXISTS idx_product_variants_product ON product_variants (product_id);
