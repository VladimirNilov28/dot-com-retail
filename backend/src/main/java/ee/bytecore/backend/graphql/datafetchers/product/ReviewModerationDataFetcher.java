package ee.bytecore.backend.graphql.datafetchers.product;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.graphql.mappers.ReviewMapper;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.ProductRatingService;

import com.netflix.dgs.codegen.generated.types.ModerationReview;
import com.netflix.dgs.codegen.generated.types.ModerationReviewPage;
import com.netflix.dgs.codegen.generated.types.ReviewStatus;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;

/** Staff review moderation; catalog managers own product-page content, admins everything. */
@DgsComponent
public class ReviewModerationDataFetcher {
    private static final String MODERATOR =
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).REVIEW_MODERATE)"
                    + " && hasAnyRole('CATALOG_MANAGER','ADMIN')";

    private final ProductRatingService reviews;
    private final CurrentUserProvider currentUser;

    public ReviewModerationDataFetcher(ProductRatingService reviews, CurrentUserProvider currentUser) {
        this.reviews = reviews;
        this.currentUser = currentUser;
    }

    @DgsQuery
    @PreAuthorize(MODERATOR)
    public ModerationReviewPage moderationReviews(
            @InputArgument ReviewStatus status, @InputArgument Integer page, @InputArgument Integer size) {
        return ReviewMapper.toModerationPage(reviews.moderationReviews(
                status == null ? null : status.name(), page == null ? 0 : page, size == null ? 20 : size));
    }

    @DgsMutation
    @PreAuthorize(MODERATOR)
    public ModerationReview hideReview(@InputArgument String reviewId, @InputArgument String reason) {
        return ReviewMapper.toModerationType(reviews.hide(
                currentUser.getCurrentUserId(), ProductReviewDataFetcher.parseId(reviewId, "review"), reason));
    }

    @DgsMutation
    @PreAuthorize(MODERATOR)
    public ModerationReview restoreReview(@InputArgument String reviewId) {
        return ReviewMapper.toModerationType(
                reviews.restore(currentUser.getCurrentUserId(), ProductReviewDataFetcher.parseId(reviewId, "review")));
    }
}
