package ee.bytecore.backend.security.ratelimit;

import java.util.Arrays;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    PostgresRateLimiter postgresRateLimiter(JdbcTemplate jdbc, RateLimitProperties properties, Environment env) {
        if (!properties.isEnabled() && Arrays.asList(env.getActiveProfiles()).contains("prod"))
            throw new IllegalStateException("Rate limiting cannot be disabled in the prod profile");
        return new PostgresRateLimiter(jdbc, properties.getCleanupIdleAfter());
    }

    @Bean
    RateLimitFilter rateLimitFilter(RateLimitProperties properties, PostgresRateLimiter limiter, JsonMapper mapper) {
        return new RateLimitFilter(
                properties,
                limiter,
                new ClientAddressResolver(properties.getTrustedProxies(), properties.getClientIpHeader()),
                mapper);
    }

    /** Registered inside the Spring Security chain by SecurityConfig; never as a standalone servlet filter. */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
