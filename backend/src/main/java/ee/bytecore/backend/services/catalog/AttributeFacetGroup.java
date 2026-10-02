package ee.bytecore.backend.services.catalog;

import java.util.List;

/** All observed values (with counts) for a single variant-attribute name, e.g. "color". */
public record AttributeFacetGroup(String name, List<AttributeValueCount> values) {}
