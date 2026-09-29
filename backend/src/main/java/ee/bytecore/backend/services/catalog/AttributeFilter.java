package ee.bytecore.backend.services.catalog;

/** A single requested `name = value` variant-attribute constraint. */
public record AttributeFilter(String name, String value) {}
