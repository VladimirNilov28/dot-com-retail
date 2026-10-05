package ee.bytecore.backend.graphql.mappers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ee.bytecore.backend.entities.category.Category;

public class CategoryMapper {

    public static com.netflix.dgs.codegen.generated.types.Category toGraphQlType(Category entity) {
        if (entity == null) {
            return null;
        }
        List<Category> ancestors = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        for (Category ancestor = entity; ancestor != null; ancestor = ancestor.getParent()) {
            if (!visited.add(ancestor.getId())) {
                throw new IllegalArgumentException(String.format(
                        "Category hierarchy contains a cycle at category %s; an administrator must repair the parent link",
                        ancestor.getId()));
            }
            ancestors.add(ancestor);
        }
        com.netflix.dgs.codegen.generated.types.Category parent = null;
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            Category ancestor = ancestors.get(i);
            parent = com.netflix.dgs.codegen.generated.types.Category.newBuilder()
                    .id(ancestor.getId().toString())
                    .name(ancestor.getName())
                    .slug(ancestor.getSlug())
                    ._parent(parent)
                    .createdAt(ancestor.getCreatedAt())
                    .updatedAt(ancestor.getUpdatedAt())
                    .build();
        }
        return parent;
    }
}
