package ee.bytecore.backend.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties("cart.guest")
@Getter
@Setter
public class GuestCartSettings {
    private Duration ttl = Duration.ofDays(30);
    private Duration cleanupInterval = Duration.ofHours(1);
    private Duration mergeReceiptTtl = Duration.ofDays(30);
    private int cleanupBatchSize = 100;
    private boolean secure = true;
    private List<String> allowedOrigins = List.of();

    @PostConstruct
    void validate() {
        for (Duration duration : List.of(ttl, cleanupInterval, mergeReceiptTtl)) {
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("Guest cart durations must be positive");
            }
        }
        if (cleanupBatchSize <= 0) {
            throw new IllegalArgumentException("Guest cart cleanup batch size must be positive");
        }
        for (String origin : allowedOrigins) {
            java.net.URI uri = java.net.URI.create(origin);
            if (!List.of("https", "http").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())) {
                throw new IllegalArgumentException("Guest cart allowed origins must be exact HTTP(S) origins");
            }
        }
    }
}
