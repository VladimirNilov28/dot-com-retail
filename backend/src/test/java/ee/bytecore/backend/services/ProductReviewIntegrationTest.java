package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.exceptions.ReviewPurchaseRequiredException;
import ee.bytecore.backend.repositories.product.ProductReviewRow;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.services.catalog.CatalogSearchCriteria;
import ee.bytecore.backend.services.catalog.ProductSortOption;
import ee.bytecore.backend.services.review.ReviewEligibility;
import ee.bytecore.backend.services.review.ReviewPage;
import ee.bytecore.backend.services.review.ReviewSortOption;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@SpringBootTest(properties = {"spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false"})
@Import(PostgresTestConfiguration.class)
@Tag("integration")
@Timeout(60)
class ProductReviewIntegrationTest {
    private static final String BODY = "Solid product, works as described.";

    @Autowired
    ProductRatingService reviews;

    @Autowired
    ProductService products;

    @Autowired
    CatalogSearchService search;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private final String prefix = "review-" + UUID.randomUUID();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanFixtures() {
        PurchaseFixture.cleanup(jdbc, userIds);
        productIds.forEach(id -> jdbc.update("DELETE FROM products WHERE id = ?", id));
        userIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    @Test
    void shouldCreateTrimmedReviewAndEditOnlyTheCallersOwnRowTest() {
        Long productId = product().getId();
        Long alice = buyer(productId);
        Long bob = buyer(productId);
        ProductReviewRow created =
                reviews.upsertMyReview(alice, productId, 4, "  Great value \t", "\r\n  " + BODY + "\r\nSecond line. ");
        assertThat(created.title()).isEqualTo("Great value");
        assertThat(created.body()).isEqualTo(BODY + "\nSecond line.");
        assertThat(created.status()).isEqualTo("VISIBLE");
        assertThat(created.userId()).isEqualTo(alice);

        ProductReviewRow edited = reviews.upsertMyReview(alice, productId, 2, "Changed my mind", BODY);
        assertThat(edited.id()).isEqualTo(created.id());
        assertThat(edited.stars()).isEqualTo(2);
        assertThat(edited.createdAt()).isEqualTo(created.createdAt());

        ProductReviewRow bobs = reviews.upsertMyReview(bob, productId, 5, "Bob's take", BODY);
        assertThat(bobs.id()).isNotEqualTo(created.id());
        assertThat(reviews.myReview(alice, productId).orElseThrow().title()).isEqualTo("Changed my mind");
        assertThat(reviews.myReview(bob, productId).orElseThrow().title()).isEqualTo("Bob's take");

        assertThat(reviews.deleteMyReview(bob, productId)).isTrue();
        assertThat(reviews.deleteMyReview(bob, productId)).isFalse();
        assertThat(reviews.myReview(bob, productId)).isEmpty();
        assertThat(reviews.myReview(alice, productId)).isPresent();
        assertAggregate(productId, 2.0, 1);
    }

    @Test
    void shouldTurnLegacyStarOnlyRatingIntoAReviewAndBackViaRateProductTest() {
        Long productId = product().getId();
        Long alice = buyer(productId);
        reviews.rate(alice, productId, 3);
        ProductReviewRow legacy = reviews.myReview(alice, productId).orElseThrow();
        assertThat(legacy.title()).isNull();
        assertThat(legacy.body()).isNull();
        assertThat(reviews.productReviews(null, productId, ReviewSortOption.NEWEST, 0, 10)
                        .items())
                .isEmpty();

        reviews.upsertMyReview(alice, productId, 5, "Now with words", BODY);
        reviews.rate(alice, productId, 4);
        ProductReviewRow afterRate = reviews.myReview(alice, productId).orElseThrow();
        assertThat(afterRate.stars()).isEqualTo(4);
        assertThat(afterRate.title()).isEqualTo("Now with words");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "' '|" + BODY + "|Review title must be between 1 and 120 characters",
                "Title|too short|Review text must be between 10 and 4000 characters",
                "<b>Bold</b>|" + BODY + "|Review title must be plain text without HTML",
                "Title|Nice <script>alert(1)</script> thing|Review text must be plain text without HTML",
                "Title|<!-- a comment that is long -->|Review text must be plain text without HTML",
                "Title|Has a bell \u0007 character inside|Review text contains unsupported characters",
            })
    void shouldRejectInvalidReviewTextWithoutPersistenceTest(String title, String body, String message) {
        Long productId = product().getId();
        Long alice = user();
        assertThatThrownBy(() -> reviews.upsertMyReview(alice, productId, 4, title, body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        assertThat(reviews.myReview(alice, productId)).isEmpty();
    }

    @Test
    void shouldValidateStarsLengthsAndKeepHarmlessAngleBracketsTest() {
        Long productId = product().getId();
        Long alice = buyer(productId);
        for (int stars : new int[] {0, 6}) {
            assertThatThrownBy(() -> reviews.upsertMyReview(alice, productId, stars, "Title", BODY))
                    .hasMessage("Rating stars must be between 1 and 5");
        }
        assertThatThrownBy(() -> reviews.upsertMyReview(alice, productId, 4, "x".repeat(121), BODY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reviews.upsertMyReview(alice, productId, 4, "Title", "x".repeat(4001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reviews.upsertMyReview(alice, productId, 4, null, BODY))
                .hasMessage("Review title is required");
        assertThatThrownBy(() -> reviews.upsertMyReview(alice, productId, 4, "Line\nbreak", BODY))
                .hasMessage("Review title must be a single line");
        assertThatThrownBy(() -> reviews.upsertMyReview(alice, 999_999_999L, 4, "Title", BODY))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> reviews.upsertMyReview(null, productId, 4, "Title", BODY))
                .isInstanceOf(AccessDeniedException.class);

        ProductReviewRow ok = reviews.upsertMyReview(alice, productId, 4, "x".repeat(120), "I <3 it, 5 > 4 & fine");
        assertThat(ok.body()).isEqualTo("I <3 it, 5 > 4 & fine");
        assertThat(reviews.upsertMyReview(alice, productId, 4, "Max", "y".repeat(4000))
                        .body())
                .hasSize(4000);
    }

    @Test
    void shouldEnforceReviewConstraintsInTheDatabaseTest() {
        Long productId = product().getId();
        Long alice = buyer(productId);
        String insert = "INSERT INTO product_ratings(product_id,user_id,stars,title,body,status,moderation_reason,"
                + "moderated_at) VALUES (?,?,4,?,?,?,?,?)";
        List<Object[]> invalid = List.of(
                new Object[] {productId, alice, "Title", null, "VISIBLE", null, null},
                new Object[] {productId, alice, null, BODY, "VISIBLE", null, null},
                new Object[] {productId, alice, "Title", "too short", "VISIBLE", null, null},
                new Object[] {productId, alice, " Untrimmed", BODY, "VISIBLE", null, null},
                new Object[] {productId, alice, "Title", BODY, "DELETED", null, null},
                new Object[] {productId, alice, "Title", BODY, "HIDDEN", null, null});
        for (Object[] row : invalid) {
            assertThatThrownBy(() -> jdbc.update(insert, row)).isInstanceOf(DataIntegrityViolationException.class);
        }
        long id = reviews.upsertMyReview(alice, productId, 4, "Title", BODY).id();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO review_votes(review_id,user_id) VALUES (?,?)", id, -1L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldCountEachVoterOnceRejectSelfVotesAndUnvoteTest() {
        Long productId = product().getId();
        Long author = buyer(productId);
        Long voter = user();
        long id = reviews.upsertMyReview(author, productId, 4, "Title", BODY).id();

        assertThatThrownBy(() -> reviews.vote(author, id))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("You cannot vote on your own review");
        ProductReviewRow voted = reviews.vote(voter, id);
        assertThat(voted.helpfulCount()).isEqualTo(1);
        assertThat(voted.viewerHasVoted()).isTrue();
        assertThat(reviews.vote(voter, id).helpfulCount()).isEqualTo(1);

        ProductReviewRow asAuthor = reviews.productReviews(author, productId, ReviewSortOption.NEWEST, 0, 10)
                .items()
                .getFirst();
        assertThat(asAuthor.helpfulCount()).isEqualTo(1);
        assertThat(asAuthor.viewerHasVoted()).isFalse();

        ProductReviewRow unvoted = reviews.unvote(voter, id);
        assertThat(unvoted.helpfulCount()).isZero();
        assertThat(unvoted.viewerHasVoted()).isFalse();
        assertThat(reviews.unvote(voter, id).helpfulCount()).isZero();
        assertThatThrownBy(() -> reviews.vote(voter, 999_999_999L)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> reviews.vote(null, id)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void shouldKeepConcurrentVotesUniquePerUserTest() throws Exception {
        Long productId = product().getId();
        long id = reviews.upsertMyReview(buyer(productId), productId, 4, "Title", BODY)
                .id();
        Long sameVoter = user();
        List<Long> voters = new ArrayList<>(List.of(sameVoter, sameVoter, sameVoter, sameVoter));
        for (int i = 0; i < 4; i++) voters.add(user());
        CyclicBarrier barrier = new CyclicBarrier(voters.size());
        ExecutorService executor = Executors.newFixedThreadPool(voters.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Long voter : voters) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return reviews.vote(voter, id);
                }));
            }
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_votes WHERE review_id = ?", Long.class, id))
                .isEqualTo(5);
        assertThat(reviews.vote(sameVoter, id).helpfulCount()).isEqualTo(5);
    }

    @Test
    void shouldExcludeHiddenReviewsFromAggregatesSortListingsAndVotesUntilRestoredTest() {
        Product product = product();
        Long productId = product.getId();
        Product other = product();
        Long alice = buyer(productId);
        Long bob = buyer(productId);
        Long carol = buyer(productId);
        Long moderator = user();
        PurchaseFixture.paid(jdbc, alice, other.getId());
        long aliceReview =
                reviews.upsertMyReview(alice, productId, 5, "Love it", BODY).id();
        reviews.upsertMyReview(bob, productId, 4, "Decent", BODY);
        reviews.rate(carol, productId, 1);
        reviews.rate(alice, other.getId(), 3);

        ReviewPage before = reviews.productReviews(null, productId, ReviewSortOption.NEWEST, 0, 10);
        assertThat(before.items()).hasSize(2);
        assertThat(before.pageInfo().totalItems()).isEqualTo(2);
        assertThat(before.summary().ratingCount()).isEqualTo(3);
        assertThat(before.summary().averageRating()).isCloseTo(10.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(before.summary().counts())
                .containsExactly(Map.entry(5, 1), Map.entry(4, 1), Map.entry(3, 0), Map.entry(2, 0), Map.entry(1, 1));
        assertThat(ratingSortedIds()).containsExactly(productId, other.getId());

        ProductReviewRow hidden = reviews.hide(moderator, aliceReview, "  Off-topic content ");
        assertThat(hidden.status()).isEqualTo("HIDDEN");
        assertThat(hidden.moderationReason()).isEqualTo("Off-topic content");
        assertThat(hidden.moderatedAt()).isNotNull();
        assertAggregate(productId, 2.5, 2);
        ReviewPage after = reviews.productReviews(null, productId, ReviewSortOption.NEWEST, 0, 10);
        assertThat(after.items()).extracting(ProductReviewRow::title).containsExactly("Decent");
        assertThat(after.summary().ratingCount()).isEqualTo(2);
        assertThat(after.summary().counts().get(5)).isZero();
        assertThat(ratingSortedIds()).containsExactly(other.getId(), productId);
        assertThatThrownBy(() -> reviews.vote(bob, aliceReview)).isInstanceOf(EntityNotFoundException.class);
        assertThat(reviews.myReview(alice, productId).orElseThrow().status()).isEqualTo("HIDDEN");
        assertThat(reviews.upsertMyReview(alice, productId, 5, "Edited", BODY).status())
                .as("editing a hidden review must not self-restore it")
                .isEqualTo("HIDDEN");
        assertThat(reviews.moderationReviews("HIDDEN", 0, 20).items())
                .extracting(ProductReviewRow::id)
                .contains(aliceReview);

        assertThat(reviews.restore(moderator, aliceReview).status()).isEqualTo("VISIBLE");
        assertAggregate(productId, 10.0 / 3, 3);
        assertThat(ratingSortedIds()).containsExactly(productId, other.getId());
        assertThat(reviews.productReviews(null, productId, ReviewSortOption.NEWEST, 0, 10)
                        .items())
                .hasSize(2);
        assertThat(jdbc.queryForObject(
                        "SELECT moderation_reason FROM product_ratings WHERE id = ?", String.class, aliceReview))
                .isNull();

        List<Map<String, Object>> audit = jdbc.queryForList(
                "SELECT operation, details->>'reason' AS reason FROM admin_audit_log"
                        + " WHERE target_type = 'PRODUCT_REVIEW' AND target_id = ? ORDER BY id",
                Long.toString(aliceReview));
        assertThat(audit).extracting(row -> row.get("operation")).containsExactly("REVIEW_HIDE", "REVIEW_RESTORE");
        assertThat(audit.getFirst().get("reason")).isEqualTo("Off-topic content");
    }

    @Test
    void shouldValidateModerationInputTest() {
        Long productId = product().getId();
        long id = reviews.upsertMyReview(buyer(productId), productId, 4, "Title", BODY)
                .id();
        assertThatThrownBy(() -> reviews.hide(null, id, "  "))
                .hasMessage("Moderation reason must be between 3 and 500 characters");
        assertThatThrownBy(() -> reviews.hide(null, id, "<i>spam</i>")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reviews.hide(null, 999_999_999L, "Spam content"))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> reviews.moderationReviews("DELETED", 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM admin_audit_log WHERE target_type = 'PRODUCT_REVIEW' AND target_id = ?",
                        Long.class,
                        Long.toString(id)))
                .isZero();
    }

    @Test
    void shouldPaginateAndSortVisibleReviewsTest() {
        Long productId = product().getId();
        List<Long> authors = new ArrayList<>();
        int[] stars = {3, 5, 1, 4};
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < stars.length; i++) {
            Long author = buyer(productId);
            authors.add(author);
            ids.add(reviews.upsertMyReview(author, productId, stars[i], "Review " + i, BODY)
                    .id());
        }
        reviews.vote(authors.get(0), ids.get(2));
        reviews.vote(authors.get(1), ids.get(2));
        reviews.vote(authors.get(2), ids.get(0));

        assertThat(titles(productId, ReviewSortOption.NEWEST, 0, 10))
                .containsExactly("Review 3", "Review 2", "Review 1", "Review 0");
        assertThat(titles(productId, ReviewSortOption.RATING_HIGH, 0, 10))
                .containsExactly("Review 1", "Review 3", "Review 0", "Review 2");
        assertThat(titles(productId, ReviewSortOption.RATING_LOW, 0, 10))
                .containsExactly("Review 2", "Review 0", "Review 3", "Review 1");
        assertThat(titles(productId, ReviewSortOption.HELPFUL, 0, 2)).containsExactly("Review 2", "Review 0");
        ReviewPage second = reviews.productReviews(null, productId, ReviewSortOption.HELPFUL, 1, 2);
        assertThat(second.items()).extracting(ProductReviewRow::title).containsExactly("Review 3", "Review 1");
        assertThat(second.pageInfo().totalItems()).isEqualTo(4);
        assertThat(second.pageInfo().totalPages()).isEqualTo(2);

        assertThatThrownBy(() -> reviews.productReviews(null, productId, ReviewSortOption.NEWEST, 0, 21))
                .hasMessage("Page size must be between 1 and 20");
        assertThatThrownBy(() -> reviews.productReviews(null, productId, ReviewSortOption.NEWEST, -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reviews.productReviews(null, 999_999_999L, ReviewSortOption.NEWEST, 0, 10))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void shouldNotPublishEmailLikeUsernamesAndShouldCascadeVotesOnDeleteTest() {
        Long productId = product().getId();
        String email = prefix + "@example.com";
        Long author = users.save(User.create(email, "other-" + email, LocalDate.of(2000, 1, 1)))
                .getId();
        userIds.add(author);
        PurchaseFixture.paid(jdbc, author, productId);
        Long voter = user();
        long id = reviews.upsertMyReview(author, productId, 4, "Title", BODY).id();
        reviews.vote(voter, id);
        assertThat(reviews.productReviews(null, productId, ReviewSortOption.NEWEST, 0, 10)
                        .items()
                        .getFirst()
                        .authorName())
                .isEqualTo("Customer");
        assertThat(reviews.deleteMyReview(author, productId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_votes WHERE review_id = ?", Long.class, id))
                .isZero();
    }

    @ParameterizedTest(name = "{0}/{1} refund={2}")
    @CsvSource({
        "PAID,SUCCEEDED,",
        "PROCESSING,SUCCEEDED,",
        "SHIPPING,SUCCEEDED,",
        "COMPLETED,SUCCEEDED,",
        "COMPLETED,SUCCEEDED,PENDING",
        "PAID,SUCCEEDED,FAILED",
    })
    void shouldLetCustomersWithAPaidNonRefundedOrderCreateReviewsTest(String status, String payment, String refund) {
        Long productId = product().getId();
        Long buyer = user();
        long order = PurchaseFixture.order(jdbc, buyer, productId, status, payment);
        if (refund != null) PurchaseFixture.refund(jdbc, order, refund);

        assertThat(reviews.eligibility(buyer, productId)).isEqualTo(new ReviewEligibility(true, false, null));
        ProductReviewRow created = reviews.upsertMyReview(buyer, productId, 5, "Bought it", BODY);
        assertThat(created.verifiedPurchase()).isTrue();
        assertThat(reviews.eligibility(buyer, productId)).isEqualTo(new ReviewEligibility(true, true, null));
        assertThat(reviews.productReviews(null, productId, ReviewSortOption.NEWEST, 0, 10)
                        .items())
                .singleElement()
                .satisfies(row -> assertThat(row.verifiedPurchase()).isTrue());
    }

    @Test
    void shouldKeepQualifyingAfterARejectedRefundRequestTest() {
        Long productId = product().getId();
        Long buyer = user();
        long order = PurchaseFixture.order(jdbc, buyer, productId, "COMPLETED", "SUCCEEDED");
        PurchaseFixture.rejectedRefundRequest(jdbc, order);
        assertThat(reviews.upsertMyReview(buyer, productId, 4, "Kept it", BODY).verifiedPurchase())
                .isTrue();
    }

    @ParameterizedTest(name = "{0}/{1} refund={2}")
    @CsvSource({
        // unpaid / awaiting payment
        "PENDING,UNKNOWN,",
        "PENDING,PENDING,",
        "PENDING,UNRESOLVED,",
        // declined payment cancels the order
        "CANCELLED,FAILED,",
        // cancelled before payment
        "CANCELLED,UNKNOWN,",
        // cancelled after payment and refunded
        "CANCELLED,SUCCEEDED,SUCCEEDED",
        // refunded without cancellation
        "COMPLETED,SUCCEEDED,SUCCEEDED",
    })
    void shouldRejectReviewsAndRatingsWithoutAQualifyingPurchaseTest(String status, String payment, String refund) {
        Long productId = product().getId();
        Long customer = user();
        long order = PurchaseFixture.order(jdbc, customer, productId, status, payment);
        if (refund != null) PurchaseFixture.refund(jdbc, order, refund);

        assertNoPurchase(customer, productId);
    }

    @Test
    void shouldNotCountOtherProductsOrOtherCustomersOrdersAsAPurchaseTest() {
        Long productId = product().getId();
        Long otherProduct = product().getId();
        Long customer = user();
        Long someoneElse = user();
        PurchaseFixture.paid(jdbc, customer, otherProduct);
        PurchaseFixture.paid(jdbc, someoneElse, productId);

        assertNoPurchase(customer, productId);
        assertNoPurchase(user(), productId);
        assertThatThrownBy(() -> reviews.eligibility(null, productId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reviews.eligibility(customer, 999_999_999L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void shouldKeepPrePolicyReviewsVisibleAndEditableWithoutAPurchaseTest() {
        Long productId = product().getId();
        Long legacyAuthor = user();
        Long legacyRater = user();
        jdbc.update(
                "INSERT INTO product_ratings(product_id,user_id,stars,title,body) VALUES (?,?,3,'Old review',?)",
                productId,
                legacyAuthor,
                BODY);
        jdbc.update("INSERT INTO product_ratings(product_id,user_id,stars) VALUES (?,?,2)", productId, legacyRater);

        ProductReviewRow listed = reviews.productReviews(null, productId, ReviewSortOption.NEWEST, 0, 10)
                .items()
                .getFirst();
        assertThat(listed.title()).isEqualTo("Old review");
        assertThat(listed.verifiedPurchase()).isFalse();
        assertThat(reviews.eligibility(legacyAuthor, productId)).isEqualTo(new ReviewEligibility(true, true, null));
        assertThat(reviews.upsertMyReview(legacyAuthor, productId, 4, "Edited old review", BODY)
                        .title())
                .isEqualTo("Edited old review");
        assertThat(reviews.rate(legacyAuthor, productId, 5).getRatingCount()).isEqualTo(2);

        // A legacy star-only rating may change its stars, but adding text is creating a review.
        assertThat(reviews.rate(legacyRater, productId, 1).getRatingCount()).isEqualTo(2);
        assertThat(reviews.eligibility(legacyRater, productId))
                .isEqualTo(new ReviewEligibility(false, false, ReviewEligibility.PURCHASE_REQUIRED));
        assertThatThrownBy(() -> reviews.upsertMyReview(legacyRater, productId, 4, "Now with text", BODY))
                .isInstanceOf(ReviewPurchaseRequiredException.class);
        assertThat(reviews.myReview(legacyRater, productId).orElseThrow().body())
                .isNull();
    }

    @Test
    void shouldSortByHelpfulVotesThenNewestThenIdWithinBoundedPagesTest() {
        Long productId = product().getId();
        List<Long> authors = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Long author = buyer(productId);
            authors.add(author);
            ids.add(reviews.upsertMyReview(author, productId, 3, "Review " + i, BODY)
                    .id());
        }
        // Reviews 0-2 share one timestamp so only the id breaks their tie; review 3 is older, 4 newest.
        jdbc.update(
                "UPDATE product_ratings SET created_at = TIMESTAMPTZ '2026-01-02 00:00:00Z' WHERE id IN (?,?,?)",
                ids.get(0),
                ids.get(1),
                ids.get(2));
        jdbc.update(
                "UPDATE product_ratings SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00Z' WHERE id = ?", ids.get(3));
        jdbc.update(
                "UPDATE product_ratings SET created_at = TIMESTAMPTZ '2026-01-03 00:00:00Z' WHERE id = ?", ids.get(4));
        Long voterA = user();
        Long voterB = user();
        reviews.vote(voterA, ids.get(3));
        reviews.vote(voterB, ids.get(3));
        reviews.vote(voterA, ids.get(0));
        reviews.vote(voterA, ids.get(2));

        // 3 (2 votes); then 0 and 2 (1 vote, same timestamp -> higher id first); then 4 (newer) before 1.
        assertThat(titles(productId, ReviewSortOption.HELPFUL, 0, 20))
                .containsExactly("Review 3", "Review 2", "Review 0", "Review 4", "Review 1");
        assertThat(titles(productId, ReviewSortOption.HELPFUL, 0, 2)).containsExactly("Review 3", "Review 2");
        assertThat(titles(productId, ReviewSortOption.HELPFUL, 1, 2)).containsExactly("Review 0", "Review 4");
        ReviewPage last = reviews.productReviews(null, productId, ReviewSortOption.HELPFUL, 2, 2);
        assertThat(last.items()).extracting(ProductReviewRow::title).containsExactly("Review 1");
        assertThat(last.pageInfo().totalItems()).isEqualTo(5);
        assertThat(last.pageInfo().totalPages()).isEqualTo(3);
        assertThat(reviews.productReviews(null, productId, ReviewSortOption.HELPFUL, 3, 2)
                        .items())
                .isEmpty();
        assertThatThrownBy(() -> reviews.productReviews(null, productId, ReviewSortOption.HELPFUL, 0, 21))
                .hasMessage("Page size must be between 1 and 20");
        assertThatThrownBy(() -> reviews.productReviews(null, productId, ReviewSortOption.HELPFUL, 0, 0))
                .hasMessage("Page size must be between 1 and 20");
        assertThat(ProductRatingService.MAX_PAGE_SIZE).isEqualTo(20);
    }

    private void assertNoPurchase(Long customer, Long productId) {
        assertThat(reviews.eligibility(customer, productId))
                .isEqualTo(new ReviewEligibility(false, false, ReviewEligibility.PURCHASE_REQUIRED));
        assertThatThrownBy(() -> reviews.upsertMyReview(customer, productId, 5, "Never bought", BODY))
                .isInstanceOf(ReviewPurchaseRequiredException.class)
                .hasMessage("Only customers who purchased this product can review it");
        assertThatThrownBy(() -> reviews.rate(customer, productId, 5))
                .isInstanceOf(ReviewPurchaseRequiredException.class);
        assertThat(reviews.myReview(customer, productId)).isEmpty();
        assertAggregate(productId, null, 0);
    }

    private List<String> titles(Long productId, ReviewSortOption sort, int page, int size) {
        return reviews.productReviews(null, productId, sort, page, size).items().stream()
                .map(ProductReviewRow::title)
                .toList();
    }

    private List<Long> ratingSortedIds() {
        return search
                .search(new CatalogSearchCriteria(
                        prefix, null, null, null, null, null, ProductSortOption.RATING_DESC, 0, 20))
                .items()
                .stream()
                .map(Product::getId)
                .toList();
    }

    private Product product() {
        String name = prefix + "-" + productIds.size();
        Product result = products.create(name, name, null, null);
        productIds.add(result.getId());
        return result;
    }

    private Long buyer(Long productId) {
        Long id = user();
        PurchaseFixture.paid(jdbc, id, productId);
        return id;
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
        if (average == null) {
            assertThat(product.getAverageRating()).isNull();
        } else {
            assertThat(product.getAverageRating()).isCloseTo(average, org.assertj.core.data.Offset.offset(1e-9));
        }
        assertThat(product.getRatingCount()).isEqualTo(count);
    }
}
