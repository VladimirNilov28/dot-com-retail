package ee.bytecore.backend.repositories.product;

import org.springframework.stereotype.Repository;

import ee.bytecore.backend.entities.product.Product;

import jakarta.persistence.EntityManager;

@Repository
public class ProductRatingRepository {
    private final EntityManager entityManager;

    public ProductRatingRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
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
}
