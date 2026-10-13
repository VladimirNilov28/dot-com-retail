package ee.bytecore.backend.integration.payment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ee.bytecore.backend.exceptions.CheckoutException;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class StripePaymentClient {
    public record Configuration(boolean available, String publishableKey) {}

    public record Session(String status, String publishableKey, String clientSecret, String failureCode) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final String token;

    public StripePaymentClient(
            @Value("${payment.service-url:http://localhost:8081}") String baseUrl,
            @Value("${payment.internal-token:}") String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public Configuration configuration() {
        if (token.length() < 32) return new Configuration(false, "");
        return call("/internal/stripe/config", "{}", Configuration.class);
    }

    public Session session(UUID requestEventId) {
        return call("/internal/stripe/session", "{\"requestEventId\":\"" + requestEventId + "\"}", Session.class);
    }

    private <T> T call(String path, String body, Class<T> type) {
        if (token.length() < 32)
            throw new CheckoutException("PAYMENT_NOT_CONFIGURED", "Sandbox payment is not configured");
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new CheckoutException("PAYMENT_UNAVAILABLE", "Sandbox payment is temporarily unavailable");
            return JSON.readValue(response.body(), type);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CheckoutException("PAYMENT_UNAVAILABLE", "Sandbox payment request was interrupted");
        } catch (java.io.IOException exception) {
            throw new CheckoutException("PAYMENT_UNAVAILABLE", "Sandbox payment is temporarily unavailable");
        }
    }
}
