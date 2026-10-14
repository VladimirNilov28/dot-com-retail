package ee.bytecore.backend.graphql.datafetchers.admin;

import static ee.bytecore.backend.services.audit.AuditDetails.details;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.services.ProductImportService;
import ee.bytecore.backend.services.audit.AuditService;

import com.netflix.dgs.codegen.generated.types.ProductImportError;
import com.netflix.dgs.codegen.generated.types.ProductImportFormat;
import com.netflix.dgs.codegen.generated.types.ProductImportResult;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;

@DgsComponent
public class ProductImportMutation {
    private final ProductImportService importService;
    private final AuditService auditService;

    public ProductImportMutation(ProductImportService importService, AuditService auditService) {
        this.importService = importService;
        this.auditService = auditService;
    }

    @DgsMutation
    @Transactional
    @PreAuthorize(
            "hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).PRODUCT_WRITE) && hasAnyRole('CATALOG_MANAGER','ADMIN')")
    public ProductImportResult importProducts(
            @InputArgument ProductImportFormat format, @InputArgument String content) {
        ProductImportService.Format parsed = format == null ? null : ProductImportService.Format.valueOf(format.name());
        ProductImportService.Result result = importService.importProducts(parsed, content);
        // One summary record per import; a rejected import writes nothing else, so recording it is safe.
        auditService.record(
                "importProducts",
                "PRODUCT_IMPORT",
                null,
                result.success() ? "SUCCESS" : "FAILED",
                details(
                        "format", parsed == null ? null : parsed.name(),
                        "bytes", content == null ? 0 : content.length(),
                        "productsCreated", result.productsCreated(),
                        "variantsCreated", result.variantsCreated(),
                        "brandsCreated", result.brandsCreated(),
                        "errorCount", result.errors().size()));
        return ProductImportResult.newBuilder()
                .success(result.success())
                .productsCreated(result.productsCreated())
                .variantsCreated(result.variantsCreated())
                .brandsCreated(result.brandsCreated())
                .errors(result.errors().stream()
                        .map(error -> ProductImportError.newBuilder()
                                .row(error.row())
                                .field(error.field())
                                .message(error.message())
                                .build())
                        .toList())
                .build();
    }
}
