package ee.bytecore.backend.repositories.product;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.services.review.ReviewSortOption;

import jakarta.persistence.EntityManager;

@Repository
public class ProductRatingRepository {
    // author_name is the public handle; a username that looks like an e-mail address is never published.
    private static final String SELECT_REVIEW =
            """
            SELECT r.id, r.product_id, p.name AS product_name, p.slug AS product_slug, r.user_id,
                   CASE WHEN position('@' IN u.username) > 0 THEN 'Customer' ELSE u.username END AS author_name,
                   r.stars, r.title, r.body, r.status,
                   (SELECT count(*) FROM review_votes v WHERE v.review_id = r.id) AS helpful_count,
                   EXISTS (SELECT 1 FROM review_votes v
                           WHERE v.review_id = r.id AND v.user_id = CAST(:viewer AS bigint)) AS viewer_voted,
                   r.created_at, r.updated_at, r.moderation_reason, r.moderated_at
            FROM product_ratings r
            JOIN products p ON p.id = r.product_id
            JOIN users u ON u.id = r.user_id
            """;

    private final EntityManager entityManager;
    private final NamedParameterJdbcTemplate jdbc;

    public ProductRatingRepository(EntityManager entityManager, NamedParameterJdbcTemplate jdbc) {
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    public void submit(Product product, Long userId, int stars) {
        // PostgreSQL's unique-key upsert serializes competing submissions for the same owner/product.
        entityManager
                .createNativeQuery(
                        """
                INSERT INTO product_ratings (product_id, user_id, stars)
                VALUES (:productId, :userId, :stars)
                ON CONFLICT (product_id, user_id) DO UPDATE SET stars = EXCLUDED.stars
                """)
                .setParameter("productId", product.getId())
                .setParameter("userId", userId)
                .setParameter("stars", stars)
                .executeUpdate();
        entityManager.refresh(product);
    }

    /** Creates or replaces the owner's rating and written review; moderation status is left untouched. */
    public long upsertReview(long productId, long userId, int stars, String title, String body) {
        entityManager.flush();
        Long id = jdbc.queryForObject(
                """
                INSERT INTO product_ratings (product_id, user_id, stars, title, body)
                VALUES (:productId, :userId, :stars, :title, :body)
                ON CONFLICT (product_id, user_id)
                DO UPDATE SET stars = EXCLUDED.stars, title = EXCLUDED.title, body = EXCLUDED.body
                RETURNING id
                """,
                new MapSqlParameterSource()
                        .addValue("productId", productId)
                        .addValue("userId", userId)
                        .addValue("stars", stars)
                        .addValue("title", title)
                        .addValue("body", body),
                Long.class);
        return id == null ? 0L : id;
    }

    public boolean deleteOwn(long productId, long userId) {
        return jdbc.update(
                        "DELETE FROM product_ratings WHERE product_id = :productId AND user_id = :userId",
                        Map.of("productId", productId, "userId", userId))
                > 0;
    }

    public Optional<ProductReviewRow> findById(long reviewId, Long viewerId) {
        return jdbc
                .query(SELECT_REVIEW + " WHERE r.id = :id", params(viewerId).addValue("id", reviewId), this::row)
                .stream()
                .findFirst();
    }

    public Optional<ProductReviewRow> findOwn(long productId, long userId) {
        return jdbc
                .query(
                        SELECT_REVIEW + " WHERE r.product_id = :productId AND r.user_id = :userId",
                        params(userId).addValue("productId", productId).addValue("userId", userId),
                        this::row)
                .stream()
                .findFirst();
    }

    /** Locks the review row (shared) so it cannot be deleted or re-moderated mid-vote. */
    public Optional<ReviewOwnership> lockForVote(long reviewId) {
        return jdbc
                .query(
                        "SELECT user_id, status, body IS NOT NULL AS written, product_id FROM product_ratings"
                                + " WHERE id = :id FOR SHARE",
                        Map.of("id", reviewId),
                        (rs, n) -> new ReviewOwnership(
                                rs.getLong("user_id"),
                                rs.getLong("product_id"),
                                rs.getString("status"),
                                rs.getBoolean("written")))
                .stream()
                .findFirst();
    }

    /** Locks the review row for a moderation change. */
    public Optional<ReviewOwnership> lockForModeration(long reviewId) {
        return jdbc
                .query(
                        "SELECT user_id, status, body IS NOT NULL AS written, product_id FROM product_ratings"
                                + " WHERE id = :id FOR UPDATE",
                        Map.of("id", reviewId),
                        (rs, n) -> new ReviewOwnership(
                                rs.getLong("user_id"),
                                rs.getLong("product_id"),
                                rs.getString("status"),
                                rs.getBoolean("written")))
                .stream()
                .findFirst();
    }

    /** Idempotent: the (review_id, user_id) key turns duplicate or concurrent votes into no-ops. */
    public void vote(long reviewId, long userId) {
        jdbc.update(
                "INSERT INTO review_votes (review_id, user_id) VALUES (:reviewId, :userId) ON CONFLICT DO NOTHING",
                Map.of("reviewId", reviewId, "userId", userId));
    }

    public void unvote(long reviewId, long userId) {
        jdbc.update(
                "DELETE FROM review_votes WHERE review_id = :reviewId AND user_id = :userId",
                Map.of("reviewId", reviewId, "userId", userId));
    }

    public void moderate(long reviewId, String status, Long moderatorId, String reason) {
        jdbc.update(
                """
                UPDATE product_ratings
                SET status = :status, moderated_by = :moderator, moderated_at = NOW(), moderation_reason = :reason
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("status", status)
                        .addValue("moderator", moderatorId)
                        .addValue("reason", reason)
                        .addValue("id", reviewId));
    }

    /** Visible written reviews of one product. */
    public List<ProductReviewRow> findVisibleWritten(
            long productId, Long viewerId, ReviewSortOption sort, int page, int size) {
        String order =
                switch (sort) {
                    case NEWEST -> "r.created_at DESC, r.id DESC";
                    case HELPFUL -> "helpful_count DESC, r.created_at DESC, r.id DESC";
                    case RATING_HIGH -> "r.stars DESC, r.created_at DESC, r.id DESC";
                    case RATING_LOW -> "r.stars ASC, r.created_at DESC, r.id DESC";
                };
        return jdbc.query(
                SELECT_REVIEW
                        + " WHERE r.product_id = :productId AND r.status = 'VISIBLE' AND r.body IS NOT NULL"
                        + " ORDER BY " + order + " LIMIT :limit OFFSET :offset",
                params(viewerId)
                        .addValue("productId", productId)
                        .addValue("limit", size)
                        .addValue("offset", (long) page * size),
                this::row);
    }

    public long countVisibleWritten(long productId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM product_ratings WHERE product_id = :productId AND status = 'VISIBLE'"
                        + " AND body IS NOT NULL",
                Map.of("productId", productId),
                Long.class);
        return count == null ? 0 : count;
    }

    /** Star value -> number of VISIBLE ratings (written or star-only). */
    public Map<Integer, Integer> visibleDistribution(long productId) {
        Map<Integer, Integer> counts = new HashMap<>();
        jdbc.query(
                "SELECT stars, count(*) AS n FROM product_ratings WHERE product_id = :productId AND status = 'VISIBLE'"
                        + " GROUP BY stars",
                Map.of("productId", productId),
                rs -> {
                    counts.put(rs.getInt("stars"), rs.getInt("n"));
                });
        return counts;
    }

    public List<ProductReviewRow> findForModeration(String status, int page, int size) {
        return jdbc.query(
                SELECT_REVIEW
                        + " WHERE (CAST(:status AS varchar) IS NULL OR r.status = CAST(:status AS varchar))"
                        + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit OFFSET :offset",
                params(null).addValue("status", status).addValue("limit", size).addValue("offset", (long) page * size),
                this::row);
    }

    public long countForModeration(String status) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM product_ratings"
                        + " WHERE (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))",
                new MapSqlParameterSource("status", status),
                Long.class);
        return count == null ? 0 : count;
    }

    private MapSqlParameterSource params(Long viewerId) {
        return new MapSqlParameterSource("viewer", viewerId);
    }

    private ProductReviewRow row(ResultSet rs, int rowNum) throws SQLException {
        return new ProductReviewRow(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getString("product_slug"),
                rs.getLong("user_id"),
                rs.getString("author_name"),
                rs.getInt("stars"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("status"),
                rs.getLong("helpful_count"),
                rs.getBoolean("viewer_voted"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                rs.getString("moderation_reason"),
                instant(rs.getTimestamp("moderated_at")));
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record ReviewOwnership(long userId, long productId, String status, boolean written) {}
}
