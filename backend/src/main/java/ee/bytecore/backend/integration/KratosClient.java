package ee.bytecore.backend.integration;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ee.bytecore.backend.exceptions.IdentitySyncException;
import ee.bytecore.backend.exceptions.UserAlreadyExistsException;

/**
 * Thin client for Kratos's Admin API. Registration links identities through
 * metadata_admin.spring_user_id. Deletion verifies that unique link and uses
 * a durably saved identity UUID for retries. Profile email synchronization
 * retains its existing email-based lookup.
 */
@Component
public class KratosClient {

    private final RestClient restClient;

    public KratosClient(@Value("${kratos.admin-url:http://kratos:4434}") String kratosAdminUrl) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(kratosAdminUrl)
                .requestFactory(requestFactory)
                .build();
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
        } catch (HttpClientErrorException.Conflict e) {
            throw new UserAlreadyExistsException(
                    "An authentication account already exists for this email. Use account recovery or contact support.");
        } catch (RestClientException e) {
            throw new IdentitySyncException(
                    "Registration could not contact the identity provider. Retry registration.");
        }
    }

    public UUID findLinkedIdentity(Long springUserId) {
        if (springUserId == null || springUserId <= 0) {
            throw new IllegalArgumentException("A canonical user id is required");
        }
        UUID linked = null;
        String pageToken = null;
        var seenTokens = new HashSet<String>();
        try {
            // Enumerate the metadata links, not email matches: email can change,
            // and duplicate links must fail closed rather than deleting one arbitrarily.
            for (int page = 0; page < 10000; page++) {
                String currentToken = pageToken;
                var response = restClient
                        .get()
                        .uri(builder -> builder.path("/admin/identities")
                                .queryParam("page_size", 250)
                                .queryParamIfPresent("page_token", java.util.Optional.ofNullable(currentToken))
                                .build())
                        .retrieve()
                        .toEntity(List.class);
                List<Map<String, Object>> identities = response.getBody();
                if (identities == null) {
                    throw new IdentitySyncException("Identity lookup returned an invalid response. Retry deletion.");
                }
                for (Map<String, Object> identity : identities) {
                    if (isLinked(identity, springUserId)) {
                        if (linked != null) {
                            throw new IdentitySyncException(
                                    "Multiple canonical identity links found. Contact support.");
                        }
                        linked = UUID.fromString(String.valueOf(identity.get("id")));
                    }
                }
                pageToken = nextPageToken(response.getHeaders().get("Link"));
                if (pageToken == null) {
                    if (linked == null) {
                        throw new IdentitySyncException("No verified canonical identity link found. Contact support.");
                    }
                    return linked;
                }
                if (!seenTokens.add(pageToken)) {
                    throw new IdentitySyncException("Identity pagination did not advance. Retry deletion.");
                }
            }
            throw new IdentitySyncException("Identity lookup could not be completed. Contact support.");
        } catch (RestClientException | IllegalArgumentException e) {
            throw new IdentitySyncException("Identity lookup failed. Retry deletion or contact support.");
        }
    }

    private String nextPageToken(List<String> links) {
        if (links == null) {
            return null;
        }
        var pattern = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?");
        for (String link : links) {
            var matcher = pattern.matcher(link);
            if (matcher.find()) {
                // Never follow an upstream-provided URL/host; use only its cursor.
                String query = URI.create(matcher.group(1)).getRawQuery();
                if (query != null) {
                    for (String field : query.split("&")) {
                        String[] parts = field.split("=", 2);
                        if (parts.length == 2 && parts[0].equals("page_token") && !parts[1].isBlank()) {
                            return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                        }
                    }
                }
                throw new IdentitySyncException("Identity pagination returned an invalid cursor. Retry deletion.");
            }
        }
        return null;
    }

    public void deleteLinkedIdentity(UUID identityId, Long springUserId) {
        if (identityId == null || springUserId == null || springUserId <= 0) {
            throw new IllegalArgumentException("A verified identity and canonical user id are required");
        }
        try {
            var response = restClient
                    .get()
                    .uri("/admin/identities/{id}", identityId)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, upstreamResponse) -> {})
                    .toEntity(Map.class);
            if (response.getStatusCode().value() == 404) {
                return; // A durable, previously verified retry coordinate is already gone.
            }
            Map<String, Object> identity = response.getBody();
            if (identity == null) {
                throw new IdentitySyncException("Identity lookup returned an invalid response. Retry deletion.");
            }
            if (!isLinked(identity, springUserId)) {
                throw new IdentitySyncException("Canonical identity link changed. Contact support.");
            }
            restClient
                    .delete()
                    .uri("/admin/identities/{id}/sessions", identityId)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, upstreamResponse) -> {})
                    .toBodilessEntity();
            restClient
                    .delete()
                    .uri("/admin/identities/{id}", identityId)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, upstreamResponse) -> {})
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IdentitySyncException("Identity revocation could not be completed. Retry deletion.");
        }
    }

    private boolean isLinked(Map<String, Object> identity, Long springUserId) {
        return identity.get("metadata_admin") instanceof Map<?, ?> metadata
                && springUserId != null
                && springUserId.toString().equals(String.valueOf(metadata.get("spring_user_id")));
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
            throw new IdentitySyncException("Email synchronization could not be completed. Retry the profile update.");
        }
    }
}
