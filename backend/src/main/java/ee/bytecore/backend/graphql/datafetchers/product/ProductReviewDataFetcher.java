package ee.bytecore.backend.graphql.datafetchers.product;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.graphql.mappers.ReviewMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.ProductRatingService;
import ee.bytecore.backend.services.review.ReviewSortOption;

import com.netflix.dgs.codegen.generated.types.ProductReview;
import com.netflix.dgs.codegen.generated.types.ProductReviewPage;
import com.netflix.dgs.codegen.generated.types.ReviewSort;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;

/** Customer-facing reviews: public reads, caller-owned writes and helpful votes. */
@DgsComponent
public class ProductReviewDataFetcher {
    private final ProductRatingService reviews;
    private final CurrentUserProvider currentUser;

    public ProductReviewDataFetcher(ProductRatingService reviews, CurrentUserProvider currentUser) {
        this.reviews = reviews;
        this.currentUser = currentUser;
    }

    // Public like the catalog; an authenticated caller additionally gets viewer-specific flags.
    @DgsQuery
    @PreAuthorize("permitAll()")
    public ProductReviewPage productReviews(
            @InputArgument String productId,
            @InputArgument Integer page,
            @InputArgument Integer size,
            @InputArgument ReviewSort sort) {
        Long viewer = currentUser.getCurrentUserId();
        return ReviewMapper.toGraphQlType(
                reviews.productReviews(
                        viewer,
                        parseId(productId, "product"),
                        sort == null ? ReviewSortOption.NEWEST : ReviewSortOption.valueOf(sort.name()),
                        page == null ? 0 : page,
                        size == null ? 10 : size),
                viewer);
    }

    @DgsQuery
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).RATING_WRITE)")
    public ProductReview myReview(@InputArgument String productId) {
        Long viewer = currentUser.getCurrentUserId();
        return reviews.myReview(viewer, parseId(productId, "product"))
                .map(row -> ReviewMapper.toGraphQlType(row, viewer))
                .orElse(null);
    }

    @DgsMutation
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).RATING_WRITE)")
    public ProductReview upsertMyReview(
            @InputArgument String productId,
            @InputArgument int stars,
            @InputArgument String title,
            @InputArgument String body) {
        Long viewer = currentUser.getCurrentUserId();
        return ReviewMapper.toGraphQlType(
                reviews.upsertMyReview(viewer, parseId(productId, "product"), stars, title, body), viewer);
    }

    @DgsMutation
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).RATING_WRITE)")
    public boolean deleteMyReview(@InputArgument String productId) {
        return reviews.deleteMyReview(currentUser.getCurrentUserId(), parseId(productId, "product"));
    }

    @DgsMutation
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).RATING_WRITE)")
    public ProductReview voteReviewHelpful(@InputArgument String reviewId) {
        Long viewer = currentUser.getCurrentUserId();
        return ReviewMapper.toGraphQlType(reviews.vote(viewer, parseId(reviewId, "review")), viewer);
    }

    @DgsMutation
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).RATING_WRITE)")
    public ProductReview unvoteReviewHelpful(@InputArgument String reviewId) {
        Long viewer = currentUser.getCurrentUserId();
        return ReviewMapper.toGraphQlType(reviews.unvote(viewer, parseId(reviewId, "review")), viewer);
    }

    static long parseId(String value, String label) {
        try {
            long id = Long.parseLong(value);
            if (id < 1) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label + " id");
        }
    }
}
