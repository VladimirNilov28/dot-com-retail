package ee.bytecore.backend.services.catalog;

import java.math.BigDecimal;

/** Min/max effective price across the current matching product population. Null when empty. */
public record PriceRange(BigDecimal min, BigDecimal max) {}
