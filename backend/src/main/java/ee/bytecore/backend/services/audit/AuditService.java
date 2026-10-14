package ee.bytecore.backend.services.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Appends privileged-operation records to {@code admin_audit_log}. Call it inside the
 * same transaction as the change (so a rolled-back change leaves no SUCCESS record).
 * Details must contain safe metadata only (ids, field names, old/new non-secret values).
 */
@Service
public class AuditService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String operation, String targetType, Object targetId, Map<String, ?> details) {
        record(operation, targetType, targetId, "SUCCESS", details);
    }

    public void record(String operation, String targetType, Object targetId, String outcome, Map<String, ?> details) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String subject = auth == null || auth.getName() == null ? "system" : auth.getName();
        Long userId = null;
        try {
            userId = Long.valueOf(subject);
        } catch (NumberFormatException ignored) {
            // non-numeric subjects (e.g. machine clients) are kept only as subject text
        }
        String roles = auth == null
                ? null
                : auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .filter(a -> a.startsWith("ROLE_"))
                        .sorted()
                        .collect(Collectors.joining(","));
        String json;
        try {
            json = JSON.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        jdbc.update(
                "INSERT INTO admin_audit_log(actor_user_id, actor_subject, actor_roles, operation, target_type, target_id, outcome, details)"
                        + " VALUES (?,?,?,?,?,?,?,?::jsonb)",
                userId,
                subject,
                roles,
                operation,
                targetType,
                targetId == null ? null : String.valueOf(targetId),
                outcome,
                json);
    }

    public record Entry(
            long id,
            Instant occurredAt,
            Long actorUserId,
            String actorSubject,
            String actorUsername,
            String actorRoles,
            String operation,
            String targetType,
            String targetId,
            String outcome,
            Map<String, Object> details) {}

    public record EntryPage(List<Entry> items, long totalElements, int page, int size) {}

    /**
     * Newest-first, bounded page of audit records. Filters are exact matches; {@code actor}
     * matches the actor's subject or (case-insensitively) current username.
     */
    public EntryPage search(String operation, String targetType, String actor, int page, int size) {
        if (page < 0 || page > 10_000) throw new IllegalArgumentException("Page must be between 0 and 10000");
        if (size < 1 || size > 100) throw new IllegalArgumentException("Size must be between 1 and 100");
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (operation != null && !operation.isBlank()) {
            where.append(" AND a.operation = ?");
            args.add(operation.trim());
        }
        if (targetType != null && !targetType.isBlank()) {
            where.append(" AND a.target_type = ?");
            args.add(targetType.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (actor != null && !actor.isBlank()) {
            where.append(" AND (a.actor_subject = ? OR lower(u.username) = lower(?))");
            args.add(actor.trim());
            args.add(actor.trim());
        }
        String from = " FROM admin_audit_log a LEFT JOIN users u ON u.id = a.actor_user_id";
        Long total = jdbc.queryForObject("SELECT count(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<Entry> items = jdbc.query(
                "SELECT a.id, a.occurred_at, a.actor_user_id, a.actor_subject, u.username, a.actor_roles,"
                        + " a.operation, a.target_type, a.target_id, a.outcome, a.details::text AS details"
                        + from + where + " ORDER BY a.occurred_at DESC, a.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new Entry(
                        rs.getLong("id"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getObject("actor_user_id", Long.class),
                        rs.getString("actor_subject"),
                        rs.getString("username"),
                        rs.getString("actor_roles"),
                        rs.getString("operation"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getString("outcome"),
                        parse(rs.getString("details"))),
                pageArgs.toArray());
        return new EntryPage(items, total == null ? 0 : total, page, size);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        try {
            return json == null ? Map.of() : JSON.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
