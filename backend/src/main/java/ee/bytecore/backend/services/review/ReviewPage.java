package ee.bytecore.backend.services.review;

import java.util.List;

import ee.bytecore.backend.repositories.product.ProductReviewRow;
import ee.bytecore.backend.services.catalog.SearchPageInfo;

/**
 * A page of reviews. {@code summary} is present for public product listings and
 * covers every VISIBLE rating of the product (star-only ratings included).
 */
public record ReviewPage(List<ProductReviewRow> items, SearchPageInfo pageInfo, RatingSummary summary) {}
