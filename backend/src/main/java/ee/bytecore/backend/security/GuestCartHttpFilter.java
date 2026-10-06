package ee.bytecore.backend.security;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import ee.bytecore.backend.config.GuestCartSettings;

import graphql.language.*;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class GuestCartHttpFilter extends OncePerRequestFilter {
    public static final String PUBLIC_CATALOG_CACHEABLE_ATTRIBUTE =
            GuestCartHttpFilter.class.getName() + ".publicCatalogCacheable";

    private static final Set<String> PUBLIC_CATALOG_FIELDS =
            Set.of("product", "products", "category", "categories", "searchProducts", "productSearchSuggestions");
    private static final Set<String> GUEST_FIELDS = Set.of(
            "guestCart",
            "startGuestCart",
            "addGuestCartItem",
            "updateGuestCartItem",
            "removeGuestCartItem",
            "checkoutShippingOptions",
            "guestCheckoutPreview",
            "createGuestOrder",
            "guestOrder",
            "cancelGuestOrder");

    private record OperationSelection(OperationDefinition.Operation operation, Set<String> roots) {}

    private final GuestCartSettings settings;
    private final JsonMapper mapper;

    public GuestCartHttpFilter(GuestCartSettings settings, JsonMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/graphql".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();
        HttpServletRequest replay = new BodyRequest(request, body);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        OperationSelection selection = selectedOperation(body);
        if (selection == null) {
            if (!authenticated) {
                reject(response, 400, "A valid GraphQL operation must be selected");
                return;
            }
            chain.doFilter(replay, response);
            return;
        }

        Set<String> roots = selection.roots();
        boolean publicCatalogRoot = roots.stream().anyMatch(PUBLIC_CATALOG_FIELDS::contains);
        boolean guestOperation =
                roots.stream().anyMatch(field -> GUEST_FIELDS.contains(field) || "mergeGuestCart".equals(field));
        if (publicCatalogRoot && guestOperation) {
            reject(response, 400, "Public catalog and guest-cart roots cannot be combined in one operation");
            return;
        }
        if (!authenticated
                && publicCatalogRoot
                && (selection.operation() != OperationDefinition.Operation.QUERY
                        || roots.isEmpty()
                        || !PUBLIC_CATALOG_FIELDS.containsAll(roots))) {
            reject(response, 400, "Public catalog reads cannot be combined with protected or guest operations");
            return;
        }
        if (!authenticated
                && !publicCatalogRoot
                && (roots.isEmpty()
                        || selection.operation() == OperationDefinition.Operation.SUBSCRIPTION
                        || !GUEST_FIELDS.containsAll(roots))) {
            reject(response, 401, "Authentication is required");
            return;
        }

        if (publicCatalogRoot) {
            boolean credentialFree =
                    !authenticated && request.getHeader("Authorization") == null && request.getHeader("Cookie") == null;
            if (credentialFree) {
                request.setAttribute(PUBLIC_CATALOG_CACHEABLE_ATTRIBUTE, Boolean.TRUE);
                response.setHeader("Cache-Control", "public, max-age=60");
                response.addHeader("Vary", "Origin, Authorization, Cookie");
            } else {
                response.setHeader("Cache-Control", "private, no-store");
            }
        }

        if (guestOperation) {
            if (!settings.getAllowedOrigins().contains(request.getHeader("Origin"))
                    || !"1".equals(request.getHeader("X-Guest-Cart-Request"))
                    || request.getContentType() == null
                    || !MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(
                            request.getContentType().split(";", 2)[0].trim())) {
                reject(response, 403, "Guest cart requires an allowed Origin and X-Guest-Cart-Request header");
                return;
            }
            String credential = null;
            String orderCredential = null;
            for (String header : java.util.Collections.list(request.getHeaders("Cookie"))) {
                for (String entry : header.split(";")) {
                    String[] pair = entry.trim().split("=", 2);
                    if (pair.length == 2 && GuestCartContext.COOKIE.equals(pair[0])) {
                        if (credential != null) {
                            reject(response, 403, "Ambiguous guest cart cookie");
                            return;
                        }
                        credential = pair[1];
                    }
                    if (pair.length == 2 && GuestCartContext.ORDER_COOKIE.equals(pair[0])) {
                        if (orderCredential != null) {
                            reject(response, 403, "Ambiguous guest order cookie");
                            return;
                        }
                        orderCredential = pair[1];
                    }
                }
            }
            replay.setAttribute(GuestCartContext.KEY, new GuestCartContext(credential, orderCredential, settings));
            response.setHeader("Cache-Control", "private, no-store");
        }
        if (roots.stream()
                .anyMatch(field -> Set.of("checkoutPreview", "createOrder", "checkoutOrder", "order", "myOrders")
                                .contains(field)
                        || Set.of("cancelOrder", "cancelOrderAsStaff", "updateOrderStatus")
                                .contains(field))) response.setHeader("Cache-Control", "private, no-store");
        chain.doFilter(replay, response);
    }

    private OperationSelection selectedOperation(byte[] body) {
        try {
            JsonNode json = mapper.readTree(body);
            if (json == null || !json.isObject() || !json.path("query").isString()) return null;
            Document document = Parser.parse(json.path("query").asString());
            List<OperationDefinition> operations = document.getDefinitionsOfType(OperationDefinition.class);
            String name = json.path("operationName").isString()
                    ? json.path("operationName").asString()
                    : null;
            OperationDefinition operation = name == null
                    ? operations.size() == 1 ? operations.getFirst() : null
                    : operations.stream()
                            .filter(candidate -> name.equals(candidate.getName()))
                            .findFirst()
                            .orElse(null);
            if (operation == null) return null;
            Map<String, FragmentDefinition> fragments = document.getDefinitionsOfType(FragmentDefinition.class).stream()
                    .collect(Collectors.toMap(
                            FragmentDefinition::getName, fragment -> fragment, (first, second) -> first));
            Set<String> roots = new HashSet<>();
            collect(operation.getSelectionSet(), fragments, new HashSet<>(), roots);
            return new OperationSelection(operation.getOperation(), roots);
        } catch (JacksonException | InvalidSyntaxException exception) {
            // Authenticated malformed requests retain Spring's existing error handling.
            return null;
        }
    }

    private void collect(
            SelectionSet selections,
            Map<String, FragmentDefinition> fragments,
            Set<String> visited,
            Set<String> roots) {
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

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(Map.of("errors", List.of(Map.of("message", message)))));
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BodyRequest(HttpServletRequest request, byte[] body) {
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

                public boolean isFinished() {
                    return input.available() == 0;
                }

                public boolean isReady() {
                    return true;
                }

                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Guest cart uses synchronous HTTP");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
