package ee.bytecore.backend.integration;

import java.util.List;
import java.util.Map;

import ee.bytecore.backend.exceptions.IdentitySyncException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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

    public void updateIdentityEmail(String currentEmail, String newEmail) {
        try {
            List<Map<String, Object>> identities = restClient.get()
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

            restClient.patch()
                    .uri("/admin/identities/{id}", identityId)
                    .body(List.of(Map.of("op", "replace", "path", "/traits/email", "value", newEmail)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IdentitySyncException("Failed to sync email to Kratos: " + e.getMessage(), e);
        }
    }
}
