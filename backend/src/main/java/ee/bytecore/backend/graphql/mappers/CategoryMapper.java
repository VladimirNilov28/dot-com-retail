package ee.bytecore.backend.graphql.mappers;

import ee.bytecore.backend.entities.category.Category;

public class CategoryMapper {

    public static com.netflix.dgs.codegen.generated.types.Category toGraphQlType(Category entity) {
        if (entity == null) {
            return null;
        }
        return com.netflix.dgs.codegen.generated.types.Category.newBuilder()
                .id(entity.getId().toString())
                .name(entity.getName())
                .slug(entity.getSlug())
                ._parent(toGraphQlType(entity.getParent()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
