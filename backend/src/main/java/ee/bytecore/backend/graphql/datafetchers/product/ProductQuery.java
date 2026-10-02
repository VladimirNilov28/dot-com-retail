package ee.bytecore.backend.graphql.datafetchers.product;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.graphql.mappers.CategoryMapper;
import ee.bytecore.backend.graphql.mappers.ProductMapper;
import ee.bytecore.backend.services.ProductService;
import ee.bytecore.backend.services.ProductVariantService;

import com.netflix.dgs.codegen.generated.types.Category;
import com.netflix.dgs.codegen.generated.types.Product;
import com.netflix.dgs.codegen.generated.types.ProductVariant;
import com.netflix.graphql.dgs.*;

@DgsComponent
public class ProductQuery {

    private final ProductService productService;
    private final ProductVariantService productVariantService;

    public ProductQuery(ProductService productService, ProductVariantService productVariantService) {
        this.productService = productService;
        this.productVariantService = productVariantService;
    }

    @DgsQuery
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_READ)")
    public Product product(@InputArgument String id, @InputArgument String slug) {
        if (slug != null) {
            return productService
                    .findBySlug(slug)
                    .map(ProductMapper::toGraphQlType)
                    .orElse(null);
        }
        if (id != null) {
            return productService
                    .findById(Long.valueOf(id))
                    .map(ProductMapper::toGraphQlType)
                    .orElse(null);
        }
        return null;
    }

    @DgsQuery
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_READ)")
    public List<Product> products() {
        return productService.findAll().stream()
                .map(ProductMapper::toGraphQlType)
                .toList();
    }

    @DgsData(parentType = "Product")
    public CompletableFuture<List<ProductVariant>> variants(DgsDataFetchingEnvironment dfe) {
        Product product = dfe.getSource();
        if (product == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        org.dataloader.DataLoader<Long, List<ee.bytecore.backend.entities.product.ProductVariant>> loader =
                dfe.getDataLoader("variantsByProductId");
        return loader.load(Long.valueOf(product.getId()))
                .thenApply(variants ->
                        variants.stream().map(ProductMapper::toGraphQlType).toList());
    }

    @DgsData(parentType = "Product")
    public CompletableFuture<List<Category>> categories(DgsDataFetchingEnvironment dfe) {
        Product product = dfe.getSource();
        if (product == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        org.dataloader.DataLoader<Long, List<ee.bytecore.backend.entities.category.Category>> loader =
                dfe.getDataLoader("categoriesByProductId");
        return loader.load(Long.valueOf(product.getId()))
                .thenApply(categories ->
                        categories.stream().map(CategoryMapper::toGraphQlType).toList());
    }

    /**
     * {@code ProductMapper.toGraphQlType(ProductVariant)} deliberately leaves
     * {@code product} unset (it doesn't have the owning entity's Product
     * loaded eagerly). Resolved here per-variant rather than via a
     * DataLoader: variants are only ever selected in small, single-parent
     * lists (a product's own variants, or a cart/order item's variant) - no
     * N+1 has been demonstrated yet, matching this project's
     * DataLoader-only-when-needed convention.
     */
    @DgsData(parentType = "ProductVariant", field = "product")
    public Product productForVariant(DgsDataFetchingEnvironment dfe) {
        ProductVariant variant = dfe.getSource();
        if (variant == null) {
            return null;
        }
        return productVariantService
                .findById(Long.valueOf(variant.getId()))
                .map(entity -> ProductMapper.toGraphQlType(entity.getProduct()))
                .orElse(null);
    }
}
