package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.services.catalog.AttributeFilter;
import ee.bytecore.backend.services.catalog.CatalogSearchCriteria;
import ee.bytecore.backend.services.catalog.ProductSortOption;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SpringBootTest(properties = {"spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false"})
@Import(PostgresTestConfiguration.class)
@Tag("integration")
@Timeout(60)
class ProductRatingIntegrationTest {
    @Autowired
    ProductRatingService ratings;

    @Autowired
    ProductService products;

    @Autowired
    ProductVariantService variants;

    @Autowired
    CatalogSearchService search;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final String prefix = "rating-" + UUID.randomUUID();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
        productIds.forEach(id -> jdbc.update("DELETE FROM products WHERE id = ?", id));
        userIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    @Test
    void shouldPersistRatingsAndUpdateOnlyTheCurrentOwnersRowTest() {
        Product product = product();
        Long alice = user();
        Long bob = user();
        assertAggregate(product.getId(), null, 0);
        assertThat(ratings.rate(alice, product.getId(), 5).getAverageRating()).isEqualTo(5.0);
        ratings.rate(bob, product.getId(), 3);
        assertAggregate(product.getId(), 4.0, 2);
        Product updated = ratings.rate(alice, product.getId(), 2);
        assertThat(updated.getAverageRating()).isEqualTo(2.5);
        assertThat(updated.getRatingCount()).isEqualTo(2);
        ratings.rate(alice, product.getId(), 2);
        assertAggregate(product.getId(), 2.5, 2);
        assertThat(jdbc.queryForObject(
                        "SELECT stars FROM product_ratings WHERE product_id = ? AND user_id = ?",
                        Integer.class,
                        product.getId(),
                        bob))
                .isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 6, Integer.MAX_VALUE})
    void shouldRejectOutOfRangeRatingsWithoutPersistenceTest(int stars) {
        Product product = product();
        assertThatThrownBy(() -> ratings.rate(user(), product.getId(), stars))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Rating stars must be between 1 and 5");
        assertAggregate(product.getId(), null, 0);
    }

    @Test
    void shouldEnforceDatabaseRangeUniquenessAndForeignKeysTest() {
        Product product = product();
        Long owner = user();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO product_ratings(product_id,user_id,stars) VALUES (?,?,0)", product.getId(), owner))
                .isInstanceOf(DataIntegrityViolationException.class);
        ratings.rate(owner, product.getId(), 1);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO product_ratings(product_id,user_id,stars) VALUES (?,?,5)", product.getId(), owner))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO product_ratings(product_id,user_id,stars) VALUES (?,?,5)",
                        product.getId(),
                        Long.MAX_VALUE))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertAggregate(product.getId(), 1.0, 1);
    }

    @Test
    void shouldRejectMissingInactiveAndAnonymousOwnersAndMissingProductsTest() {
        Product product = product();
        Long inactive = user();
        jdbc.update("UPDATE users SET deleted = true WHERE id = ?", inactive);
        Long pending = user();
        jdbc.update("UPDATE users SET deletion_identity_id = ? WHERE id = ?", UUID.randomUUID(), pending);
        for (Long owner : new Long[] {null, Long.MAX_VALUE, inactive, pending}) {
            assertThatThrownBy(() -> ratings.rate(owner, product.getId(), 5)).isInstanceOf(AccessDeniedException.class);
        }
        Long active = user();
        assertThatThrownBy(() -> ratings.rate(active, Long.MAX_VALUE, 5)).isInstanceOf(EntityNotFoundException.class);
        assertAggregate(product.getId(), null, 0);
    }

    @Test
    void shouldKeepAggregatesCorrectForConcurrentDistinctOwnersAndUpdatesTest() throws Exception {
        Product product = product();
        List<Long> owners = new ArrayList<>();
        for (int i = 0; i < 8; i++) owners.add(user());
        concurrentRatings(product.getId(), owners, false);
        assertAggregate(product.getId(), 2.625, 8);
        concurrentRatings(product.getId(), owners, true);
        assertAggregate(product.getId(), 3.375, 8);
        assertThat(jdbc.queryForObject(
                        "SELECT SUM(stars) FROM product_ratings WHERE product_id = ?", Integer.class, product.getId()))
                .isEqualTo(27);
    }

    @Test
    void shouldDeduplicateConcurrentSubmissionsByTheSameOwnerTest() throws Exception {
        Product product = product();
        Long owner = user();
        concurrentRatings(product.getId(), java.util.Collections.nCopies(8, owner), false);
        Product result = products.findById(product.getId()).orElseThrow();
        assertThat(result.getRatingCount()).isEqualTo(1);
        assertThat(result.getAverageRating()).isBetween(1.0, 5.0);
        assertThat(result.getAverageRating())
                .isEqualTo(jdbc.queryForObject(
                        "SELECT stars::double precision FROM product_ratings WHERE product_id = ?",
                        Double.class,
                        product.getId()));
    }

    @Test
    void shouldRecomputeAfterAccountRatingRemovalAndCascadeProductDeletionTest() {
        Product product = product();
        Long first = user();
        Long second = user();
        ratings.rate(first, product.getId(), 5);
        ratings.rate(second, product.getId(), 1);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> users.deleteRatings(first));
        assertAggregate(product.getId(), 1.0, 1);
        jdbc.update("DELETE FROM users WHERE id = ?", second);
        assertAggregate(product.getId(), null, 0);
        ratings.rate(first, product.getId(), 3);
        products.deleteById(product.getId());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM product_ratings WHERE product_id = ?", Integer.class, product.getId()))
                .isZero();
    }

    @Test
    void shouldSortTheFullPopulationWithNullsLastIdTiesAndBoundedPagesTest() {
        Product unrated = product();
        Product firstTie = product();
        Product secondTie = product();
        Product low = product();
        Long owner = user();
        Long anotherOwner = user();
        ratings.rate(owner, firstTie.getId(), 5);
        ratings.rate(anotherOwner, firstTie.getId(), 3);
        ratings.rate(owner, secondTie.getId(), 4);
        ratings.rate(owner, low.getId(), 5);
        ratings.rate(anotherOwner, low.getId(), 1);
        var first = search.search(criteria(ProductSortOption.RATING_DESC, 0, 2));
        var second = search.search(criteria(ProductSortOption.RATING_DESC, 1, 2));
        assertThat(first.items()).extracting(Product::getId).containsExactly(firstTie.getId(), secondTie.getId());
        assertThat(first.items()).extracting(Product::getAverageRating).containsExactly(4.0, 4.0);
        assertThat(first.items()).extracting(Product::getRatingCount).containsExactly(2, 1);
        assertThat(second.items()).extracting(Product::getId).containsExactly(low.getId(), unrated.getId());
        assertThat(first.pageInfo().totalItems()).isEqualTo(4);
        assertThat(first.pageInfo().totalPages()).isEqualTo(2);
        assertThat(search.search(criteria(ProductSortOption.RATING_DESC, 2, 2)).items())
                .isEmpty();
        assertThatThrownBy(() -> search.search(criteria(ProductSortOption.RATING_DESC, 0, 101)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> search.search(criteria(ProductSortOption.RATING_DESC, -1, 2)))
                .isInstanceOf(IllegalArgumentException.class);
        ratings.rate(anotherOwner, low.getId(), 5);
        assertThat(search.search(criteria(ProductSortOption.RATING_DESC, 0, 100))
                        .items())
                .extracting(Product::getId)
                .containsExactly(low.getId(), firstTie.getId(), secondTie.getId(), unrated.getId());
    }

    @Test
    void shouldPreservePriceRelevanceSameVariantFiltersAndFacetSemanticsTest() {
        Product cheap = product();
        Product expensive = product();
        Long owner = user();
        ratings.rate(owner, cheap.getId(), 1);
        ratings.rate(owner, expensive.getId(), 5);
        variants.create(cheap.getId(), prefix + "-cheap", new BigDecimal("10"), Map.of("color", "blue"), null, null);
        variants.create(
                expensive.getId(), prefix + "-expensive", new BigDecimal("40"), Map.of("color", "blue"), null, null);
        variants.create(expensive.getId(), prefix + "-red", new BigDecimal("5"), Map.of("color", "red"), null, null);
        var asc = search.search(filtered(ProductSortOption.PRICE_ASC));
        var desc = search.search(filtered(ProductSortOption.PRICE_DESC));
        var rated = search.search(filtered(ProductSortOption.RATING_DESC));
        assertThat(asc.items()).extracting(Product::getId).containsExactly(cheap.getId(), expensive.getId());
        assertThat(desc.items()).extracting(Product::getId).containsExactly(expensive.getId(), cheap.getId());
        assertThat(rated.items()).extracting(Product::getId).containsExactly(expensive.getId(), cheap.getId());
        assertThat(rated.facets()).isEqualTo(asc.facets());
        assertThat(rated.facets()).isEqualTo(desc.facets());
        assertThat(rated.facets().price().min()).isEqualByComparingTo("10");
        assertThat(rated.facets().price().max()).isEqualByComparingTo("40");
        var sameVariant = new CatalogSearchCriteria(
                prefix,
                null,
                null,
                new BigDecimal("15"),
                List.of(new AttributeFilter("color", "blue")),
                null,
                ProductSortOption.RATING_DESC,
                0,
                20);
        assertThat(search.search(sameVariant).items())
                .extracting(Product::getId)
                .containsExactly(cheap.getId());
        assertThat(search.search(criteria(ProductSortOption.RELEVANCE, 0, 20)).items())
                .extracting(Product::getId)
                .containsExactly(cheap.getId(), expensive.getId());
    }

    private void concurrentRatings(Long productId, List<Long> owners, boolean update) throws Exception {
        CyclicBarrier start = new CyclicBarrier(owners.size());
        try (var executor = Executors.newFixedThreadPool(owners.size())) {
            List<Future<Product>> futures = new ArrayList<>();
            for (int i = 0; i < owners.size(); i++) {
                Long owner = owners.get(i);
                int stars = i % 5 + 1;
                futures.add(executor.submit(() -> {
                    start.await(20, TimeUnit.SECONDS);
                    return ratings.rate(owner, productId, update ? 6 - stars : stars);
                }));
            }
            for (Future<Product> future : futures) future.get(30, TimeUnit.SECONDS);
        }
    }

    private Product product() {
        String name = prefix + "-" + productIds.size();
        Product result = products.create(name, name, null, null);
        productIds.add(result.getId());
        return result;
    }

    private Long user() {
        String name = prefix + "-user-" + userIds.size();
        Long id = users.save(User.create(name, name + "@example.com", LocalDate.of(2000, 1, 1)))
                .getId();
        userIds.add(id);
        return id;
    }

    private void assertAggregate(Long id, Double average, int count) {
        Product product = products.findById(id).orElseThrow();
        assertThat(product.getAverageRating()).isEqualTo(average);
        assertThat(product.getRatingCount()).isEqualTo(count);
    }

    private CatalogSearchCriteria criteria(ProductSortOption sort, int page, int size) {
        return new CatalogSearchCriteria(prefix, null, null, null, null, null, sort, page, size);
    }

    private CatalogSearchCriteria filtered(ProductSortOption sort) {
        return new CatalogSearchCriteria(
                prefix, null, null, null, List.of(new AttributeFilter("color", "blue")), null, sort, 0, 20);
    }
}
