package ee.bytecore.backend.services.audit;

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
        String roles = auth == null ? null : auth.getAuthorities().stream()
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
                userId, subject, roles, operation, targetType,
                targetId == null ? null : String.valueOf(targetId), outcome, json);
    }
}
