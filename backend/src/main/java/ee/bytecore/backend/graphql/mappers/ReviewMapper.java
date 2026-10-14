package ee.bytecore.backend.graphql.mappers;

import java.util.Objects;

import ee.bytecore.backend.repositories.product.ProductReviewRow;
import ee.bytecore.backend.services.catalog.SearchPageInfo;
import ee.bytecore.backend.services.review.ReviewEligibility;
import ee.bytecore.backend.services.review.ReviewPage;

import com.netflix.dgs.codegen.generated.types.ModerationReview;
import com.netflix.dgs.codegen.generated.types.ModerationReviewPage;
import com.netflix.dgs.codegen.generated.types.ProductReview;
import com.netflix.dgs.codegen.generated.types.ProductReviewPage;
import com.netflix.dgs.codegen.generated.types.RatingDistributionBucket;
import com.netflix.dgs.codegen.generated.types.ReviewIneligibleReason;
import com.netflix.dgs.codegen.generated.types.ReviewStatus;

public class ReviewMapper {

    public static ProductReview toGraphQlType(ProductReviewRow row, Long viewerId) {
        return ProductReview.newBuilder()
                .id(String.valueOf(row.id()))
                .productId(String.valueOf(row.productId()))
                .stars(row.stars())
                .title(row.title())
                .body(row.body())
                .authorName(row.authorName())
                .status(ReviewStatus.valueOf(row.status()))
                .helpfulCount(Math.toIntExact(row.helpfulCount()))
                .viewerHasVoted(row.viewerHasVoted())
                .viewerIsAuthor(viewerId != null && Objects.equals(viewerId, row.userId()))
                .verifiedPurchase(row.verifiedPurchase())
                .createdAt(row.createdAt())
                .updatedAt(row.updatedAt())
                .build();
    }

    public static com.netflix.dgs.codegen.generated.types.ReviewEligibility toGraphQlType(ReviewEligibility value) {
        return com.netflix.dgs.codegen.generated.types.ReviewEligibility.newBuilder()
                .canReview(value.canReview())
                .hasReview(value.hasReview())
                .reason(value.reason() == null ? null : ReviewIneligibleReason.valueOf(value.reason()))
                .build();
    }

    public static ProductReviewPage toGraphQlType(ReviewPage page, Long viewerId) {
        return ProductReviewPage.newBuilder()
                .items(page.items().stream()
                        .map(row -> toGraphQlType(row, viewerId))
                        .toList())
                .pageInfo(toPageInfo(page.pageInfo()))
                .averageRating(page.summary().averageRating())
                .ratingCount(page.summary().ratingCount())
                .distribution(page.summary().counts().entrySet().stream()
                        .map(entry -> RatingDistributionBucket.newBuilder()
                                .stars(entry.getKey())
                                .count(entry.getValue())
                                .build())
                        .toList())
                .build();
    }

    public static ModerationReview toModerationType(ProductReviewRow row) {
        return ModerationReview.newBuilder()
                .id(String.valueOf(row.id()))
                .productId(String.valueOf(row.productId()))
                .productName(row.productName())
                .productSlug(row.productSlug())
                .stars(row.stars())
                .title(row.title())
                .body(row.body())
                .authorName(row.authorName())
                .status(ReviewStatus.valueOf(row.status()))
                .helpfulCount(Math.toIntExact(row.helpfulCount()))
                .moderationReason(row.moderationReason())
                .moderatedAt(row.moderatedAt())
                .createdAt(row.createdAt())
                .updatedAt(row.updatedAt())
                .build();
    }

    public static ModerationReviewPage toModerationPage(ReviewPage page) {
        return ModerationReviewPage.newBuilder()
                .items(page.items().stream().map(ReviewMapper::toModerationType).toList())
                .pageInfo(toPageInfo(page.pageInfo()))
                .build();
    }

    private static com.netflix.dgs.codegen.generated.types.SearchPageInfo toPageInfo(SearchPageInfo info) {
        return com.netflix.dgs.codegen.generated.types.SearchPageInfo.newBuilder()
                .page(info.page())
                .size(info.size())
                .totalItems(Math.toIntExact(info.totalItems()))
                .totalPages(info.totalPages())
                .build();
    }
}
