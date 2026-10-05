package ee.bytecore.backend.services.catalog;

/** A single distinct attribute value observed among matching variants, with its product count. */
public record AttributeValueCount(String value, long count) {}
