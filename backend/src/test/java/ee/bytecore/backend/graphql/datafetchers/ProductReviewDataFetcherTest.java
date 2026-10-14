package ee.bytecore.backend.graphql.datafetchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.exceptions.ReviewPurchaseRequiredException;
import ee.bytecore.backend.graphql.GraphQlExceptionResolver;
import ee.bytecore.backend.graphql.datafetchers.product.ProductReviewDataFetcher;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.product.ProductReviewRow;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.ProductRatingService;
import ee.bytecore.backend.services.catalog.SearchPageInfo;
import ee.bytecore.backend.services.review.RatingSummary;
import ee.bytecore.backend.services.review.ReviewEligibility;
import ee.bytecore.backend.services.review.ReviewPage;
import ee.bytecore.backend.services.review.ReviewSortOption;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(
        classes = {
            ProductReviewDataFetcher.class,
            GraphQLConfig.class,
            GraphQlExceptionResolver.class,
            LocalDateScalar.class,
            InstantScalar.class,
            CurrentUserProvider.class
        })
@EnableDgsMockMvcTest
@AutoConfigureHttpGraphQlTester
@Tag("graphql")
class ProductReviewDataFetcherTest {

    @MockitoBean
    ProductRatingService reviews;

    @Autowired
    private GraphQlTester graphQlTester;

    @Test
    @WithMockUser(username = "7")
    void shouldRejectReviewWithoutPurchaseUsingAStableErrorCodeTest() {
        when(reviews.upsertMyReview(eq(7L), eq(3L), anyInt(), anyString(), anyString()))
                .thenThrow(new ReviewPurchaseRequiredException());

        graphQlTester
                .document(
                        """
                        mutation {
                          upsertMyReview(productId: "3", stars: 5, title: "Great", body: "Works well for me.") { id }
                        }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).singleElement().satisfies(error -> {
                    assertThat(error.getMessage()).isEqualTo("Only customers who purchased this product can review it");
                    assertThat(error.getExtensions()).containsEntry("errorType", "REVIEW_PURCHASE_REQUIRED");
                }));
    }

    @Test
    @WithMockUser(username = "7")
    void shouldExposeTheCallersReviewEligibilityTest() {
        when(reviews.eligibility(7L, 3L))
                .thenReturn(new ReviewEligibility(false, false, ReviewEligibility.PURCHASE_REQUIRED));

        graphQlTester
                .document("{ myReviewEligibility(productId: \"3\") { canReview hasReview reason } }")
                .execute()
                .path("myReviewEligibility.canReview")
                .entity(Boolean.class)
                .isEqualTo(false)
                .path("myReviewEligibility.hasReview")
                .entity(Boolean.class)
                .isEqualTo(false)
                .path("myReviewEligibility.reason")
                .entity(String.class)
                .isEqualTo("PURCHASE_REQUIRED");
    }

    @Test
    void shouldPassTheHelpfulSortAndExposeVerifiedPurchaseTest() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ProductReviewRow row = new ProductReviewRow(
                11,
                3,
                "Keyboard",
                "keyboard",
                7,
                "alice",
                5,
                "Great",
                "Works well for me.",
                "VISIBLE",
                4,
                false,
                true,
                now,
                now,
                null,
                null);
        when(reviews.productReviews(null, 3L, ReviewSortOption.HELPFUL, 0, 5))
                .thenReturn(new ReviewPage(
                        List.of(row),
                        new SearchPageInfo(0, 5, 1, 1),
                        new RatingSummary(5.0, 1, Map.of(5, 1, 4, 0, 3, 0, 2, 0, 1, 0))));

        graphQlTester
                .document(
                        """
                        { productReviews(productId: "3", size: 5, sort: HELPFUL) {
                            items { id helpfulCount verifiedPurchase }
                        } }
                        """)
                .execute()
                .path("productReviews.items[0].helpfulCount")
                .entity(Integer.class)
                .isEqualTo(4)
                .path("productReviews.items[0].verifiedPurchase")
                .entity(Boolean.class)
                .isEqualTo(true);
        verify(reviews).productReviews(null, 3L, ReviewSortOption.HELPFUL, 0, 5);
    }
}
