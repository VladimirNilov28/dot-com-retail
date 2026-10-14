package ee.bytecore.backend.graphql.datafetchers.admin;

import org.springframework.security.access.prepost.PreAuthorize;

import ee.bytecore.backend.services.audit.AuditService;

import com.netflix.dgs.codegen.generated.types.AuditLogEntry;
import com.netflix.dgs.codegen.generated.types.AuditLogFilter;
import com.netflix.dgs.codegen.generated.types.AuditLogPage;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;

@DgsComponent
public class AdminAuditQuery {
    private final AuditService auditService;

    public AdminAuditQuery(AuditService auditService) {
        this.auditService = auditService;
    }

    @DgsQuery
    @PreAuthorize("hasAuthority('SCOPE_' + T(ee.bytecore.backend.security.Scopes).AUDIT_READ) && hasRole('ADMIN')")
    public AuditLogPage adminAuditLog(
            @InputArgument AuditLogFilter filter, @InputArgument Integer page, @InputArgument Integer size) {
        AuditService.EntryPage result = auditService.search(
                filter == null ? null : filter.getOperation(),
                filter == null ? null : filter.getTargetType(),
                filter == null ? null : filter.getActor(),
                page == null ? 0 : page,
                size == null ? 25 : size);
        return AuditLogPage.newBuilder()
                .items(result.items().stream()
                        .map(entry -> AuditLogEntry.newBuilder()
                                .id(String.valueOf(entry.id()))
                                .occurredAt(entry.occurredAt())
                                .actorUserId(
                                        entry.actorUserId() == null
                                                ? null
                                                : entry.actorUserId().toString())
                                .actorSubject(entry.actorSubject())
                                .actorUsername(entry.actorUsername())
                                .actorRoles(entry.actorRoles())
                                .operation(entry.operation())
                                .targetType(entry.targetType())
                                .targetId(entry.targetId())
                                .outcome(entry.outcome())
                                .details(entry.details())
                                .build())
                        .toList())
                .totalElements((int) Math.min(Integer.MAX_VALUE, result.totalElements()))
                .page(result.page())
                .size(result.size())
                .build();
    }
}
