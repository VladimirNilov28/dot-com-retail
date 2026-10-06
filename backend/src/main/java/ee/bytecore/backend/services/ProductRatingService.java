package ee.bytecore.backend.services;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.repositories.product.ProductRatingRepository;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.repositories.user.UserRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class ProductRatingService {
    private final ProductRatingRepository ratings;
    private final ProductRepository products;
    private final UserRepository users;

    public ProductRatingService(ProductRatingRepository ratings, ProductRepository products, UserRepository users) {
        this.ratings = ratings;
        this.products = products;
        this.users = users;
    }

    @Transactional
    public Product rate(Long currentUserId, Long productId, int stars) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Active account is required");
        }
        if (stars < 1 || stars > 5) {
            throw new IllegalArgumentException("Rating stars must be between 1 and 5");
        }
        // Match self-service writes: lock the active owner against concurrent account deletion.
        users.findActiveByIdForUpdate(currentUserId)
                .orElseThrow(() -> new AccessDeniedException("Active account is required"));
        Product product = products.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product with id " + productId + " not found"));
        ratings.submit(product, currentUserId, stars);
        return product;
    }
}
