package ee.bytecore.backend.graphql.datafetchers.category;

import static ee.bytecore.backend.services.audit.AuditDetails.details;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.graphql.mappers.CategoryMapper;
import ee.bytecore.backend.services.CategoryService;
import ee.bytecore.backend.services.audit.AuditService;

import com.netflix.dgs.codegen.generated.types.Category;
import com.netflix.dgs.codegen.generated.types.CreateCategoryInput;
import com.netflix.dgs.codegen.generated.types.UpdateCategoryInput;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;

@DgsComponent
public class CategoryMutation {

    private final CategoryService categoryService;
    private final AuditService auditService;

    public CategoryMutation(CategoryService categoryService, AuditService auditService) {
        this.categoryService = categoryService;
        this.auditService = auditService;
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).CATEGORY_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    @Transactional
    public Category createCategory(@InputArgument CreateCategoryInput input) {
        Long parentId = input.getParentId() != null ? parseId(input.getParentId(), "category") : null;
        var created = categoryService.create(input.getName(), input.getSlug(), parentId);
        auditService.record(
                "createCategory",
                "CATEGORY",
                created.getId(),
                details("name", created.getName(), "slug", created.getSlug(), "parentId", parentId));
        return CategoryMapper.toGraphQlType(created);
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).CATEGORY_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    @Transactional
    public Category updateCategory(@InputArgument String categoryId, @InputArgument UpdateCategoryInput input) {
        long id = parseId(categoryId, "category");
        Long parentId = input.getParentId() != null ? parseId(input.getParentId(), "category") : null;
        boolean clearParent = Boolean.TRUE.equals(input.getClearParent());
        var updated = categoryService.update(id, input.getName(), input.getSlug(), parentId, clearParent);
        auditService.record(
                "updateCategory",
                "CATEGORY",
                id,
                details(
                        "name",
                        input.getName(),
                        "slug",
                        input.getSlug(),
                        "parentId",
                        parentId,
                        "clearParent",
                        clearParent));
        return CategoryMapper.toGraphQlType(updated);
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).CATEGORY_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    @Transactional
    public Boolean deleteCategory(@InputArgument String categoryId) {
        long id = parseId(categoryId, "category");
        long productLinks = categoryService.countProductLinks(id);
        boolean deleted = categoryService.deleteById(id);
        if (deleted)
            auditService.record("deleteCategory", "CATEGORY", id, details("detachedProductLinks", productLinks));
        return deleted;
    }

    private long parseId(String rawId, String entityName) {
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("Invalid %s id: %s", entityName, rawId));
        }
    }
}
