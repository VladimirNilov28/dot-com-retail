package ee.bytecore.backend.integration;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ee.bytecore.backend.exceptions.IdentitySyncException;

@Component
public class HydraClient {
    private final RestClient restClient;

    public HydraClient(@Value("${hydra.admin-url:http://hydra:4445}") String adminUrl) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        restClient = RestClient.builder()
                .baseUrl(adminUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public void revokeUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("A canonical user id is required");
        }
        try {
            // Subject-scoped consent revocation removes all associated token chains,
            // including refresh tokens; login-session revocation alone does not.
            restClient
                    .delete()
                    .uri(builder -> builder.path("/admin/oauth2/auth/sessions/consent")
                            .queryParam("subject", userId.toString())
                            .queryParam("all", true)
                            .build())
                    .retrieve()
                    .toBodilessEntity();
            restClient
                    .delete()
                    .uri(builder -> builder.path("/admin/oauth2/auth/sessions/login")
                            .queryParam("subject", userId.toString())
                            .build())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IdentitySyncException("Account revocation could not be completed. Retry deletion.");
        }
    }
}
