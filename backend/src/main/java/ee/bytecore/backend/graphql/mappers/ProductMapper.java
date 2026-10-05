package ee.bytecore.backend.graphql.mappers;

import java.util.LinkedHashMap;
import java.util.Map;

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;

public class ProductMapper {

    public static com.netflix.dgs.codegen.generated.types.Product toGraphQlType(Product entity) {
        if (entity == null) {
            return null;
        }
        // .categories and .variants are intentionally left unset here — both
        // are resolved by batched @DgsData(parentType = "Product") DataLoader
        // resolvers in ProductQuery instead, to avoid an N+1 lazy-load per
        // product when listing many products at once.
        return com.netflix.dgs.codegen.generated.types.Product.newBuilder()
                .id(entity.getId().toString())
                .name(entity.getName())
                .slug(entity.getSlug())
                .description(entity.getDescription())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static com.netflix.dgs.codegen.generated.types.ProductVariant toGraphQlType(ProductVariant entity) {
        if (entity == null) {
            return null;
        }
        return com.netflix.dgs.codegen.generated.types.ProductVariant.newBuilder()
                .id(entity.getId().toString())
                .sku(entity.getSku())
                .price(entity.getPrice())
                .attributes(entity.getAttributes())
                .barcode(entity.getBarcode())
                .weightGrams(entity.getWeightGrams())
                .isActive(entity.getIsActive() != null && entity.getIsActive())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static Map<String, Object> toAttributesMap(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> object)) {
            throw new IllegalArgumentException("Attributes must be a JSON object");
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Attribute names must be strings");
            }
            attributes.put(key, entry.getValue());
        }
        return attributes;
    }
}
