package ee.bytecore.backend.graphql.datafetchers.product;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.graphql.mappers.ProductMapper;
import ee.bytecore.backend.services.ProductService;
import ee.bytecore.backend.services.ProductVariantService;

import com.netflix.dgs.codegen.generated.types.CreateProductInput;
import com.netflix.dgs.codegen.generated.types.CreateProductVariantInput;
import com.netflix.dgs.codegen.generated.types.Product;
import com.netflix.dgs.codegen.generated.types.ProductVariant;
import com.netflix.dgs.codegen.generated.types.UpdateProductInput;
import com.netflix.dgs.codegen.generated.types.UpdateProductVariantInput;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;

@DgsComponent
public class ProductMutation {

    private final ProductService productService;
    private final ProductVariantService productVariantService;

    public ProductMutation(ProductService productService, ProductVariantService productVariantService) {
        this.productService = productService;
        this.productVariantService = productVariantService;
    }

    @DgsMutation
    @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Product createProduct(@InputArgument CreateProductInput input) {
        return ProductMapper.toGraphQlType(productService.create(
                input.getName(), input.getSlug(), input.getDescription(), toLongIds(input.getCategoryIds())));
    }

    @DgsMutation
    @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Product updateProduct(@InputArgument String productId, @InputArgument UpdateProductInput input) {
        long id = parseId(productId, "product");
        return ProductMapper.toGraphQlType(productService.update(
                id, input.getName(), input.getSlug(), input.getDescription(), toLongIds(input.getCategoryIds())));
    }

    @DgsMutation
    @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Boolean deleteProduct(@InputArgument String productId) {
        return productService.deleteById(parseId(productId, "product"));
    }

    @DgsMutation
    @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public ProductVariant createProductVariant(@InputArgument CreateProductVariantInput input) {
        long productId = parseId(input.getProductId(), "product");
        return ProductMapper.toGraphQlType(productVariantService.create(
                productId,
                input.getSku(),
                input.getPrice(),
                ProductMapper.toAttributesMap(input.getAttributes()),
                input.getBarcode(),
                input.getWeightGrams()));
    }

    @DgsMutation
    @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public ProductVariant updateProductVariant(
            @InputArgument String variantId, @InputArgument UpdateProductVariantInput input) {
        long id = parseId(variantId, "productVariant");
        return ProductMapper.toGraphQlType(productVariantService.update(
                id,
                input.getSku(),
                input.getPrice(),
                ProductMapper.toAttributesMap(input.getAttributes()),
                input.getBarcode(),
                input.getWeightGrams(),
                input.getIsActive()));
    }

    @DgsMutation
    @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Boolean deleteProductVariant(@InputArgument String variantId) {
        return productVariantService.deleteById(parseId(variantId, "productVariant"));
    }

    private List<Long> toLongIds(List<String> ids) {
        return ids == null ? null : ids.stream().map(Long::valueOf).toList();
    }

    private long parseId(String rawId, String entityName) {
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("Invalid %s id: %s", entityName, rawId));
        }
    }
}
