package ee.bytecore.backend.graphql.datafetchers.category;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.graphql.mappers.CategoryMapper;
import ee.bytecore.backend.services.CategoryService;

import com.netflix.dgs.codegen.generated.types.Category;
import com.netflix.dgs.codegen.generated.types.CreateCategoryInput;
import com.netflix.dgs.codegen.generated.types.UpdateCategoryInput;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;

@DgsComponent
public class CategoryMutation {

    private final CategoryService categoryService;

    public CategoryMutation(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @DgsMutation
    @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Category createCategory(@InputArgument CreateCategoryInput input) {
        Long parentId = input.getParentId() != null ? Long.valueOf(input.getParentId()) : null;
        return CategoryMapper.toGraphQlType(categoryService.create(input.getName(), input.getSlug(), parentId));
    }

    @DgsMutation
    @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Category updateCategory(@InputArgument String categoryId, @InputArgument UpdateCategoryInput input) {
        long id = parseId(categoryId, "category");
        Long parentId = input.getParentId() != null ? Long.valueOf(input.getParentId()) : null;
        return CategoryMapper.toGraphQlType(categoryService.update(id, input.getName(), input.getSlug(), parentId));
    }

    @DgsMutation
    @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Boolean deleteCategory(@InputArgument String categoryId) {
        long id = parseId(categoryId, "category");
        return categoryService.deleteById(id);
    }

    private long parseId(String rawId, String entityName) {
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("Invalid %s id: %s", entityName, rawId));
        }
    }
}
