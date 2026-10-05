package ee.bytecore.backend.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ee.bytecore.backend.security.GuestCartContext;
import ee.bytecore.backend.security.GuestCartHttpFilter;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class GuestCartWebConfig {
    @Bean
    GuestCartHttpFilter guestCartHttpFilter(GuestCartSettings settings, JsonMapper mapper) {
        return new GuestCartHttpFilter(settings, mapper);
    }

    @Bean
    FilterRegistrationBean<GuestCartHttpFilter> guestCartFilterRegistration(GuestCartHttpFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    WebGraphQlInterceptor guestCartContextInterceptor() {
        return (request, chain) -> {
            GuestCartContext context = null;
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                context = (GuestCartContext) attributes.getRequest().getAttribute(GuestCartContext.KEY);
            }
            if (context == null) return chain.next(request);
            GuestCartContext guest = context;
            request.configureExecutionInput(
                    (input, builder) -> builder.graphQLContext(values -> values.put(GuestCartContext.KEY, guest))
                            .build());
            return chain.next(request).doOnNext(response -> {
                for (String cookie : guest.responseCookies())
                    response.getResponseHeaders().add(HttpHeaders.SET_COOKIE, cookie);
                response.getResponseHeaders().set(HttpHeaders.CACHE_CONTROL, "private, no-store");
            });
        };
    }
}
