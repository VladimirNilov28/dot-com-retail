package ee.bytecore.backend.graphql.datafetchers.product;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.graphql.mappers.ProductMapper;
import ee.bytecore.backend.services.BrandService;
import ee.bytecore.backend.services.ProductImageService;
import ee.bytecore.backend.services.ProductService;
import ee.bytecore.backend.services.ProductVariantService;

import com.netflix.dgs.codegen.generated.types.Brand;
import com.netflix.dgs.codegen.generated.types.CreateProductInput;
import com.netflix.dgs.codegen.generated.types.CreateProductVariantInput;
import com.netflix.dgs.codegen.generated.types.Product;
import com.netflix.dgs.codegen.generated.types.ProductImage;
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

    private final BrandService brandService;
    private final ProductImageService productImageService;

    public ProductMutation(
            ProductService productService,
            ProductVariantService productVariantService,
            BrandService brandService,
            ProductImageService productImageService) {
        this.productService = productService;
        this.productVariantService = productVariantService;
        this.brandService = brandService;
        this.productImageService = productImageService;
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Product createProduct(@InputArgument CreateProductInput input) {
        Long brandId = input.getBrandId() == null
                ? null
                : brandService.require(parseId(input.getBrandId(), "brand")).getId();
        ee.bytecore.backend.entities.product.Product created = productService.create(
                input.getName(), input.getSlug(), input.getDescription(), toLongIds(input.getCategoryIds()));
        if (brandId != null) brandService.assign(created.getId(), brandId);
        return ProductMapper.toGraphQlType(created);
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Product updateProduct(@InputArgument String productId, @InputArgument UpdateProductInput input) {
        long id = parseId(productId, "product");
        if (input.getBrandId() != null && Boolean.TRUE.equals(input.getClearBrand())) {
            throw new IllegalArgumentException("Brand cannot be both set and cleared");
        }
        if (input.getBrandId() != null) brandService.require(parseId(input.getBrandId(), "brand"));
        ee.bytecore.backend.entities.product.Product updated = productService.update(
                id, input.getName(), input.getSlug(), input.getDescription(), toLongIds(input.getCategoryIds()));
        if (input.getBrandId() != null) brandService.assign(id, parseId(input.getBrandId(), "brand"));
        else if (Boolean.TRUE.equals(input.getClearBrand())) brandService.assign(id, null);
        return ProductMapper.toGraphQlType(updated);
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Boolean deleteProduct(@InputArgument String productId) {
        return productService.deleteById(parseId(productId, "product"));
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public ProductVariant createProductVariant(@InputArgument CreateProductVariantInput input) {
        long productId = parseId(input.getProductId(), "product");
        return ProductMapper.toGraphQlType(productVariantService.create(
                productId,
                input.getSku(),
                input.getPrice(),
                ProductMapper.toAttributesMap(input.getAttributes()),
                input.getBarcode(),
                input.getWeightGrams(),
                input.getLengthMm(),
                input.getWidthMm(),
                input.getHeightMm()));
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
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
                input.getIsActive(),
                input.getLengthMm(),
                input.getWidthMm(),
                input.getHeightMm(),
                Boolean.TRUE.equals(input.getClearDimensions())));
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Boolean deleteProductVariant(@InputArgument String variantId) {
        return productVariantService.deleteById(parseId(variantId, "productVariant"));
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Brand createBrand(@InputArgument String name) {
        return ProductMapper.toGraphQlType(brandService.getOrCreate(name));
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public ProductImage updateProductImage(@InputArgument String imageId, @InputArgument String altText) {
        return ProductMapper.toGraphQlType(productImageService.updateAlt(parseId(imageId, "productImage"), altText));
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public List<ProductImage> setPrimaryProductImage(@InputArgument String imageId) {
        return productImageService.setPrimary(parseId(imageId, "productImage")).stream()
                .map(ProductMapper::toGraphQlType)
                .toList();
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public List<ProductImage> reorderProductImages(
            @InputArgument String productId, @InputArgument List<String> imageIds) {
        return productImageService
                .reorder(
                        parseId(productId, "product"),
                        imageIds.stream().map(i -> parseId(i, "productImage")).toList())
                .stream()
                .map(ProductMapper::toGraphQlType)
                .toList();
    }

    @DgsMutation
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Boolean deleteProductImage(@InputArgument String imageId) {
        return productImageService.delete(parseId(imageId, "productImage"));
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
