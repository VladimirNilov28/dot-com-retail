package ee.bytecore.backend.graphql.datafetchers.category;

import java.util.List;

import ee.bytecore.backend.graphql.mappers.CategoryMapper;
import ee.bytecore.backend.services.CategoryService;

import com.netflix.dgs.codegen.generated.types.Category;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;

@DgsComponent
public class CategoryQuery {

    private final CategoryService categoryService;

    public CategoryQuery(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @DgsQuery
    public Category category(@InputArgument String id, @InputArgument String slug) {
        if (slug != null) {
            return categoryService
                    .findBySlug(slug)
                    .map(CategoryMapper::toGraphQlType)
                    .orElse(null);
        }
        if (id != null) {
            return categoryService
                    .findById(Long.valueOf(id))
                    .map(CategoryMapper::toGraphQlType)
                    .orElse(null);
        }
        return null;
    }

    @DgsQuery
    public List<Category> categories() {
        return categoryService.findAll().stream()
                .map(CategoryMapper::toGraphQlType)
                .toList();
    }
}
