package ee.bytecore.backend.security.ratelimit;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Token-bucket rate limiting in front of the GraphQL and REST handlers. Runs
 * inside the Spring Security chain right after bearer-token authentication so
 * that authenticated callers are keyed by their verified JWT subject; everyone
 * else is keyed by the client IP from {@link ClientAddressResolver}. Every
 * request consumes one token from the general (or proxy) bucket, and GraphQL
 * operations whose root fields are listed in a policy also consume from that
 * policy's bucket.
 */
public class RateLimitFilter extends OncePerRequestFilter {
    public static final String CODE = "RATE_LIMITED";
    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long FAILURE_LOG_INTERVAL_MS = 60_000;

    private final RateLimitProperties properties;
    private final PostgresRateLimiter limiter;
    private final ClientAddressResolver addresses;
    private final JsonMapper mapper;
    private final Map<String, TokenBucket> buckets = new LinkedHashMap<>();
    private final AtomicLong lastFailureLog = new AtomicLong();

    public RateLimitFilter(
            RateLimitProperties properties,
            PostgresRateLimiter limiter,
            ClientAddressResolver addresses,
            JsonMapper mapper) {
        this.properties = properties;
        this.limiter = limiter;
        this.addresses = addresses;
        this.mapper = mapper;
        properties.getPolicies().forEach((name, policy) -> buckets.put(name, policy.bucket()));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (!properties.isEnabled() || path.startsWith("/actuator/")) return true;
        // Registered product images are cache-friendly static content fetched in bulk by the storefront.
        if ("GET".equals(request.getMethod()) && path.startsWith("/media/products/")) return true;
        // WebSocket upgrades authenticate per connection_init; the upgrade itself carries no operation.
        return "GET".equals(request.getMethod()) && "websocket".equalsIgnoreCase(request.getHeader("Upgrade"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest forward = request;
        Set<String> roots = Set.of();
        if ("POST".equals(request.getMethod()) && "/graphql".equals(request.getServletPath())) {
            byte[] body = request.getInputStream().readAllBytes();
            forward = new CachedBodyRequest(request, body);
            roots = rootFields(body);
        }

        List<String> applicable = new ArrayList<>();
        String key = subjectKey(request, applicable);
        for (var policy : properties.getPolicies().entrySet()) {
            if (policy.getKey().equals(RateLimitProperties.GENERAL)
                    || policy.getKey().equals(RateLimitProperties.PROXY)) continue;
            if (policy.getValue().getOperations().stream().anyMatch(roots::contains)) applicable.add(policy.getKey());
        }

        for (String policy : applicable) {
            TokenBucket.Decision decision;
            try {
                decision = limiter.tryConsume(bucketKey(policy, key), buckets.get(policy), 1);
            } catch (DataAccessException exception) {
                // Fail open: an unavailable limiter store must not take the whole API down with it.
                long now = System.currentTimeMillis();
                long last = lastFailureLog.get();
                if (now - last > FAILURE_LOG_INTERVAL_MS && lastFailureLog.compareAndSet(last, now))
                    LOG.warn(
                            "Rate limiter store unavailable; allowing request ({})",
                            exception.getClass().getSimpleName());
                break;
            }
            if (!decision.allowed()) {
                reject(response, policy, decision.retryAfterSeconds());
                return;
            }
        }
        chain.doFilter(forward, response);
    }

    private String subjectKey(HttpServletRequest request, List<String> applicable) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getName() != null) {
            applicable.add(RateLimitProperties.GENERAL);
            return "sub:" + authentication.getName();
        }
        ClientAddressResolver.ClientAddress client = addresses.resolve(request);
        applicable.add(client.proxyWithoutClient() ? RateLimitProperties.PROXY : RateLimitProperties.GENERAL);
        return (client.proxyWithoutClient() ? "proxy:" : "ip:") + client.address();
    }

    static String bucketKey(String policy, String key) {
        String full = policy + "|" + key;
        if (full.length() <= 150) return full;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return policy + "|h:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void reject(HttpServletResponse response, String policy, long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("code", CODE);
        extensions.put("classification", CODE);
        extensions.put("policy", policy);
        extensions.put("retryAfterSeconds", retryAfterSeconds);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", "Too many requests. Please wait " + retryAfterSeconds + "s and try again.");
        error.put("extensions", extensions);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errors", List.of(error));
        body.put("data", null);
        response.getWriter().write(mapper.writeValueAsString(body));
    }

    /** Root field names of the selected operation (all operations when none is selected); empty if unparsable. */
    Set<String> rootFields(byte[] body) {
        try {
            JsonNode json = mapper.readTree(body);
            if (json == null || !json.isObject() || !json.path("query").isString()) return Set.of();
            Document document = Parser.parse(json.path("query").asString());
            String name = json.path("operationName").isString()
                    ? json.path("operationName").asString()
                    : null;
            Map<String, FragmentDefinition> fragments = new LinkedHashMap<>();
            document.getDefinitionsOfType(FragmentDefinition.class).forEach(f -> fragments.putIfAbsent(f.getName(), f));
            Set<String> roots = new HashSet<>();
            for (OperationDefinition operation : document.getDefinitionsOfType(OperationDefinition.class)) {
                if (name == null || name.equals(operation.getName()))
                    collect(operation.getSelectionSet(), fragments, new HashSet<>(), roots);
            }
            return roots;
        } catch (JacksonException | InvalidSyntaxException exception) {
            return Set.of();
        }
    }

    private void collect(
            SelectionSet selections,
            Map<String, FragmentDefinition> fragments,
            Set<String> visited,
            Set<String> roots) {
        if (selections == null) return;
        for (Selection<?> selection : selections.getSelections()) {
            if (selection instanceof Field field) roots.add(field.getName());
            else if (selection instanceof InlineFragment inline)
                collect(inline.getSelectionSet(), fragments, visited, roots);
            else if (selection instanceof FragmentSpread spread && visited.add(spread.getName())) {
                FragmentDefinition fragment = fragments.get(spread.getName());
                if (fragment != null) collect(fragment.getSelectionSet(), fragments, visited, roots);
            }
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                public int read() {
                    return input.read();
                }

                public int read(byte[] buffer, int offset, int length) {
                    return input.read(buffer, offset, length);
                }

                public boolean isFinished() {
                    return input.available() == 0;
                }

                public boolean isReady() {
                    return true;
                }

                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Rate limiting uses synchronous HTTP");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
