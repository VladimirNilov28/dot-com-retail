package ee.bytecore.backend.graphql.mappers;

import java.util.Map;

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ProductMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
                .attributes(toJsonNode(entity.getAttributes()))
                .barcode(entity.getBarcode())
                .weightGrams(entity.getWeightGrams())
                .isActive(entity.getIsActive() != null && entity.getIsActive())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static Map<String, Object> toAttributesMap(JsonNode node) {
        if (node == null) {
            return null;
        }
        return OBJECT_MAPPER.convertValue(
                node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private static JsonNode toJsonNode(Map<String, Object> attributes) {
        if (attributes == null) {
            return null;
        }
        return OBJECT_MAPPER.valueToTree(attributes);
    }
}
