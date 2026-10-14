package ee.bytecore.backend.services.review;

import java.util.Map;

/** Aggregate over VISIBLE ratings; {@code counts} maps every star value 1..5 to its count. */
public record RatingSummary(Double averageRating, int ratingCount, Map<Integer, Integer> counts) {}
