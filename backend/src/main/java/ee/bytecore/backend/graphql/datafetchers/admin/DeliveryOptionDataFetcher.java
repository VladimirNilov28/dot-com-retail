package ee.bytecore.backend.graphql.datafetchers.admin;

import static ee.bytecore.backend.services.audit.AuditDetails.details;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.services.DeliveryOptionService;
import ee.bytecore.backend.services.audit.AuditService;

import com.netflix.dgs.codegen.generated.types.CheckoutShippingMethod;
import com.netflix.dgs.codegen.generated.types.CreateDeliveryOptionInput;
import com.netflix.dgs.codegen.generated.types.DeliveryOption;
import com.netflix.dgs.codegen.generated.types.UpdateDeliveryOptionInput;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;

@DgsComponent
public class DeliveryOptionDataFetcher {
    private final DeliveryOptionService deliveryOptionService;
    private final AuditService auditService;

    public DeliveryOptionDataFetcher(DeliveryOptionService deliveryOptionService, AuditService auditService) {
        this.deliveryOptionService = deliveryOptionService;
        this.auditService = auditService;
    }

    @DgsQuery
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).SHIPPING_MANAGE) && hasRole('ADMIN')")
    public List<DeliveryOption> adminDeliveryOptions() {
        return deliveryOptionService.findAll().stream()
                .map(DeliveryOptionDataFetcher::toGraphQlType)
                .toList();
    }

    @DgsMutation
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).SHIPPING_MANAGE) && hasRole('ADMIN')")
    public DeliveryOption createDeliveryOption(@InputArgument CreateDeliveryOptionInput input) {
        var created = deliveryOptionService.create(
                input.getCode() == null ? null : input.getCode().name(),
                new DeliveryOptionService.Values(
                        input.getName(),
                        input.getDescription(),
                        input.getPrice(),
                        input.getCurrency(),
                        input.getEstimatedMinDays(),
                        input.getEstimatedMaxDays(),
                        input.getActive(),
                        input.getSortOrder()));
        auditService.record("createDeliveryOption", "DELIVERY_OPTION", created.getId(), snapshot(created));
        return toGraphQlType(created);
    }

    @DgsMutation
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).SHIPPING_MANAGE) && hasRole('ADMIN')")
    public DeliveryOption updateDeliveryOption(
            @InputArgument String id, @InputArgument UpdateDeliveryOptionInput input) {
        long optionId = parseId(id);
        var updated = deliveryOptionService.update(
                optionId,
                new DeliveryOptionService.Values(
                        input.getName(),
                        input.getDescription(),
                        input.getPrice(),
                        input.getCurrency(),
                        input.getEstimatedMinDays(),
                        input.getEstimatedMaxDays(),
                        input.getActive(),
                        input.getSortOrder()));
        auditService.record("updateDeliveryOption", "DELIVERY_OPTION", optionId, snapshot(updated));
        return toGraphQlType(updated);
    }

    @DgsMutation
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).SHIPPING_MANAGE) && hasRole('ADMIN')")
    public Boolean deleteDeliveryOption(@InputArgument String id) {
        long optionId = parseId(id);
        var deleted = deliveryOptionService.delete(optionId);
        auditService.record("deleteDeliveryOption", "DELIVERY_OPTION", optionId, details("code", deleted.getCode()));
        return true;
    }

    private static java.util.Map<String, Object> snapshot(ee.bytecore.backend.entities.shipping.DeliveryOption o) {
        return details(
                "code", o.getCode(),
                "name", o.getName(),
                "price", o.getPrice(),
                "estimatedMinDays", o.getEstimatedMinDays(),
                "estimatedMaxDays", o.getEstimatedMaxDays(),
                "active", o.isActive(),
                "sortOrder", o.getSortOrder());
    }

    static DeliveryOption toGraphQlType(ee.bytecore.backend.entities.shipping.DeliveryOption option) {
        return DeliveryOption.newBuilder()
                .id(String.valueOf(option.getId()))
                .code(CheckoutShippingMethod.valueOf(option.getCode()))
                .name(option.getName())
                .description(option.getDescription())
                .price(option.getPrice())
                .currency(option.getCurrency())
                .estimatedMinDays(option.getEstimatedMinDays())
                .estimatedMaxDays(option.getEstimatedMaxDays())
                .active(option.isActive())
                .sortOrder(option.getSortOrder())
                .createdAt(option.getCreatedAt())
                .updatedAt(option.getUpdatedAt())
                .build();
    }

    private static long parseId(String rawId) {
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid delivery option id: " + rawId);
        }
    }
}
