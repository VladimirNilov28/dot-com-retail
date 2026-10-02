package ee.bytecore.backend.integration;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ee.bytecore.backend.exceptions.IdentitySyncException;

/**
 * Thin client for Kratos's Admin API. Spring is authoritative for
 * username/email; this only mirrors auth-relevant fields (currently just
 * email, the Kratos login identifier) onto the existing Kratos identity —
 * found by its current email, never by an id stored on the Spring side (the
 * User table intentionally has no Kratos identity reference).
 */
@Component
public class KratosClient {

    private final RestClient restClient;

    public KratosClient(@Value("${kratos.admin-url:http://kratos:4434}") String kratosAdminUrl) {
        this.restClient = RestClient.builder().baseUrl(kratosAdminUrl).build();
    }

    /**
     * Creates a new Kratos identity for a self-registered user, linking it
     * back to the canonical Spring {@code User.id} via
     * {@code metadata_admin.spring_user_id} — the same convention the
     * dev/admin bootstrap (oauth-service) already uses. The password is
     * forwarded to Kratos's credential storage only; it is never persisted
     * or logged by Spring.
     */
    public void createIdentity(String email, String password, Long springUserId) {
        try {
            restClient
                    .post()
                    .uri("/admin/identities")
                    .body(Map.of(
                            "schema_id", "default",
                            "traits", Map.of("email", email),
                            "credentials", Map.of("password", Map.of("config", Map.of("password", password))),
                            "metadata_admin", Map.of("spring_user_id", springUserId)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IdentitySyncException("Failed to create Kratos identity for " + email + ": " + e.getMessage(), e);
        }
    }

    public void updateIdentityEmail(String currentEmail, String newEmail) {
        try {
            List<Map<String, Object>> identities = restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/admin/identities")
                            .queryParam("credentials_identifier", currentEmail)
                            .build())
                    .retrieve()
                    .body(List.class);

            if (identities == null || identities.isEmpty()) {
                throw new IdentitySyncException("No Kratos identity found for email " + currentEmail);
            }

            String identityId = String.valueOf(identities.get(0).get("id"));

            restClient
                    .patch()
                    .uri("/admin/identities/{id}", identityId)
                    .body(List.of(Map.of("op", "replace", "path", "/traits/email", "value", newEmail)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IdentitySyncException("Failed to sync email to Kratos: " + e.getMessage(), e);
        }
    }
}
