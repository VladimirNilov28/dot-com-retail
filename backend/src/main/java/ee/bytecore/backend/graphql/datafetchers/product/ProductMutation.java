package ee.bytecore.backend.graphql.datafetchers.product;

import static ee.bytecore.backend.services.audit.AuditDetails.details;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.graphql.mappers.ProductMapper;
import ee.bytecore.backend.services.BrandService;
import ee.bytecore.backend.services.ProductImageService;
import ee.bytecore.backend.services.ProductService;
import ee.bytecore.backend.services.ProductVariantService;
import ee.bytecore.backend.services.audit.AuditService;

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
    private final AuditService auditService;

    public ProductMutation(
            ProductService productService,
            ProductVariantService productVariantService,
            BrandService brandService,
            ProductImageService productImageService,
            AuditService auditService) {
        this.productService = productService;
        this.productVariantService = productVariantService;
        this.brandService = brandService;
        this.productImageService = productImageService;
        this.auditService = auditService;
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Product createProduct(@InputArgument CreateProductInput input) {
        Long brandId = input.getBrandId() == null
                ? null
                : brandService.require(parseId(input.getBrandId(), "brand")).getId();
        ee.bytecore.backend.entities.product.Product created = productService.create(
                input.getName(), input.getSlug(), input.getDescription(), toLongIds(input.getCategoryIds()));
        if (brandId != null) brandService.assign(created.getId(), brandId);
        auditService.record(
                "createProduct",
                "PRODUCT",
                created.getId(),
                details(
                        "name", created.getName(),
                        "slug", created.getSlug(),
                        "brandId", brandId,
                        "categoryIds", input.getCategoryIds()));
        return ProductMapper.toGraphQlType(created);
    }

    @DgsMutation
    @Transactional
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
        auditService.record(
                "updateProduct",
                "PRODUCT",
                id,
                details(
                        "name", input.getName(),
                        "slug", input.getSlug(),
                        "descriptionChanged", input.getDescription() != null ? true : null,
                        "brandId", input.getBrandId(),
                        "clearBrand", input.getClearBrand(),
                        "categoryIds", input.getCategoryIds()));
        return ProductMapper.toGraphQlType(updated);
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Boolean deleteProduct(@InputArgument String productId) {
        long id = parseId(productId, "product");
        boolean deleted = productService.deleteById(id);
        if (deleted) auditService.record("deleteProduct", "PRODUCT", id, details());
        return deleted;
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public ProductVariant createProductVariant(@InputArgument CreateProductVariantInput input) {
        long productId = parseId(input.getProductId(), "product");
        var created = productVariantService.create(
                productId,
                input.getSku(),
                input.getPrice(),
                ProductMapper.toAttributesMap(input.getAttributes()),
                input.getBarcode(),
                input.getWeightGrams(),
                input.getLengthMm(),
                input.getWidthMm(),
                input.getHeightMm());
        auditService.record(
                "createProductVariant",
                "PRODUCT_VARIANT",
                created.getId(),
                details("productId", productId, "sku", created.getSku(), "price", created.getPrice()));
        return ProductMapper.toGraphQlType(created);
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public ProductVariant updateProductVariant(
            @InputArgument String variantId, @InputArgument UpdateProductVariantInput input) {
        long id = parseId(variantId, "productVariant");
        var updated = productVariantService.update(
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
                Boolean.TRUE.equals(input.getClearDimensions()));
        auditService.record(
                "updateProductVariant",
                "PRODUCT_VARIANT",
                id,
                details(
                        "sku", input.getSku(),
                        "price", input.getPrice(),
                        "attributesChanged", input.getAttributes() != null ? true : null,
                        "barcode", input.getBarcode(),
                        "weightGrams", input.getWeightGrams(),
                        "isActive", input.getIsActive(),
                        "lengthMm", input.getLengthMm(),
                        "widthMm", input.getWidthMm(),
                        "heightMm", input.getHeightMm(),
                        "clearDimensions", input.getClearDimensions()));
        return ProductMapper.toGraphQlType(updated);
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Boolean deleteProductVariant(@InputArgument String variantId) {
        long id = parseId(variantId, "productVariant");
        boolean deleted = productVariantService.deleteById(id);
        if (deleted) auditService.record("deleteProductVariant", "PRODUCT_VARIANT", id, details());
        return deleted;
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Brand createBrand(@InputArgument String name) {
        var brand = brandService.getOrCreate(name);
        auditService.record("createBrand", "BRAND", brand.getId(), details("name", brand.getName()));
        return ProductMapper.toGraphQlType(brand);
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public ProductImage updateProductImage(@InputArgument String imageId, @InputArgument String altText) {
        long id = parseId(imageId, "productImage");
        var image = productImageService.updateAlt(id, altText);
        auditService.record("updateProductImage", "PRODUCT_IMAGE", id, details("altText", image.getAltText()));
        return ProductMapper.toGraphQlType(image);
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public List<ProductImage> setPrimaryProductImage(@InputArgument String imageId) {
        long id = parseId(imageId, "productImage");
        var images = productImageService.setPrimary(id);
        auditService.record("setPrimaryProductImage", "PRODUCT_IMAGE", id, details());
        return images.stream().map(ProductMapper::toGraphQlType).toList();
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public List<ProductImage> reorderProductImages(
            @InputArgument String productId, @InputArgument List<String> imageIds) {
        long id = parseId(productId, "product");
        List<Long> ids = imageIds.stream().map(i -> parseId(i, "productImage")).toList();
        var images = productImageService.reorder(id, ids);
        auditService.record("reorderProductImages", "PRODUCT", id, details("imageIds", ids));
        return images.stream().map(ProductMapper::toGraphQlType).toList();
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public Boolean deleteProductImage(@InputArgument String imageId) {
        long id = parseId(imageId, "productImage");
        boolean deleted = productImageService.delete(id);
        if (deleted) auditService.record("deleteProductImage", "PRODUCT_IMAGE", id, details());
        return deleted;
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
