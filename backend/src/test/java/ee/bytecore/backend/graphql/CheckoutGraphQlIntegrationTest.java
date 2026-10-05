package ee.bytecore.backend.graphql;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.integration.payment.PaymentOutboxPublisher;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.services.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.bootstrap-servers=127.0.0.1:1",
            "spring.kafka.listener.auto-startup=false",
            "spring.docker.compose.enabled=false"
        })
@Import(PostgresTestConfiguration.class)
@Tag("integration")
@Timeout(90)
class CheckoutGraphQlIntegrationTest {
    static final String PREVIEW =
            """
        query($input: CheckoutInput!){guestCheckoutPreview(input:$input){
          placeable quoteVersion totals{merchandiseSubtotal shippingCharge total currency}
          lines{productName sku attributes quantity unitPrice subtotal}
          details{contactEmail shipping{method pickupLocation{name}}} issues{code}
        }}
        """;
    static final String PLACE =
            """
        mutation($input: PlaceOrderInput!){createGuestOrder(input:$input){
          publicId requestId status paymentInteraction totals{total currency}
          details{contactEmail shipping{method pickupLocation{name}}}
          lines{productName quantity sku unitPrice subtotal}
        }}
        """;
    static final String CONFIRM =
            """
        query($id: UUID!){guestOrder(requestId:$id){publicId requestId status totals{total}
          details{contactEmail} lines{productName quantity}}}
        """;

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    ProductService products;

    @Autowired
    ProductVariantService variants;

    @Autowired
    InventoryService inventory;

    @Autowired
    WarehouseService warehouses;

    @Autowired
    CartService carts;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PaymentOutboxPublisher outboxPublisher;

    final HttpClient client = HttpClient.newHttpClient();
    User owner;
    ProductVariant variant;

    record Reply(JsonNode body, HttpResponse<String> http) {}

    static class Session {
        final Map<String, String> cookies = new HashMap<>();

        String cookieHeader() {
            return cookies.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining("; "));
        }

        void accept(HttpResponse<String> response) {
            response.headers().allValues("Set-Cookie").stream()
                    .flatMap(value -> HttpCookie.parse(value).stream())
                    .forEach(cookie -> {
                        if (cookie.getMaxAge() == 0) cookies.remove(cookie.getName());
                        else cookies.put(cookie.getName(), cookie.getValue());
                    });
        }
    }

    URI endpoint() {
        return URI.create("http://localhost:" + port + "/graphql");
    }

    void configureJwt() {
        doAnswer(invocation -> {
                    String token = invocation.getArgument(0);
                    String[] parts = token.split("/", -1);
                    if (parts.length != 2)
                        throw new org.springframework.security.oauth2.jwt.BadJwtException("Invalid fixture");
                    return Jwt.withTokenValue(token)
                            .header("alg", "RS256")
                            .subject(parts[0])
                            .claim("role", "USER")
                            .claim("scope", parts[1].replace("-", ":").replace("+", " "))
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(120))
                            .build();
                })
                .when(jwtDecoder)
                .decode(anyString());
    }

    @BeforeEach
    void setUp() {
        configureJwt();
        String key = UUID.randomUUID().toString();
        owner = users.save(User.create(key, key + "@example.com", LocalDate.of(1990, 1, 1)));
        var product = products.create("Checkout " + key, key, null, null);
        variant = variants.create(product.getId(), key, new BigDecimal("19.99"), Map.of("size", "M"), null, null);
        var warehouse = warehouses.create(key, "Fixture");
        inventory.setInventory(variant.getId(), warehouse.getId(), 20);
    }

    Map<String, Object> selections() {
        return Map.of("contactEmail", "guest@example.com", "shippingMethod", "PICKUP", "paymentSelection", "SIMULATED");
    }

    Session guest() throws Exception {
        Session session = new Session();
        success(post("mutation{startGuestCart{created cart{id}}}", Map.of(), session, null));
        success(post(
                "mutation($id:ID!){addGuestCartItem(input:{productVariantId:$id,quantity:2}){items{id}}}",
                Map.of("id", variant.getId().toString()),
                session,
                null));
        return session;
    }

    Map<String, Object> placement(Session session, UUID requestId) throws Exception {
        JsonNode preview = success(post(PREVIEW, Map.of("input", selections()), session, null))
                .at("/data/guestCheckoutPreview");
        assertThat(preview.at("/placeable").asBoolean()).isTrue();
        assertThat(new BigDecimal(preview.at("/totals/total").asString())).isEqualByComparingTo("39.98");
        assertThat(preview.at("/lines/0/quantity").asInt()).isEqualTo(2);
        return Map.of(
                "requestId",
                requestId.toString(),
                "acceptedQuoteVersion",
                preview.at("/quoteVersion").asString(),
                "checkout",
                selections());
    }

    @Test
    void guestCanPreviewPlaceRecoverLostResponseAndReplayWithoutSourceCookie() throws Exception {
        Session guest = guest();
        UUID requestId = UUID.randomUUID();
        Map<String, Object> input = placement(guest, requestId);
        assertThat(guest.cookies.keySet()).contains("retail_guest_cart", "retail_guest_orders");
        Session old = new Session();
        old.cookies.putAll(guest.cookies);
        Reply placed = post(PLACE, Map.of("input", input), guest, null);
        JsonNode order = success(placed).at("/data/createGuestOrder");
        assertThat(order.at("/status").asString()).isEqualTo("PENDING");
        assertThat(order.at("/paymentInteraction").asString()).isEqualTo("NONE");
        assertThat(guest.cookies.keySet()).contains("retail_guest_orders").doesNotContain("retail_guest_cart");
        assertThat(placed.http().headers().firstValue("Cache-Control").orElse(""))
                .contains("no-store");
        var recovery = success(post(CONFIRM, Map.of("id", requestId.toString()), old, null))
                .at("/data/guestOrder");
        assertThat(recovery.at("/publicId").asString())
                .isEqualTo(order.at("/publicId").asString());
        assertThat(success(post(PLACE, Map.of("input", input), old, null))
                        .at("/data/createGuestOrder/publicId")
                        .asString())
                .isEqualTo(order.at("/publicId").asString());
        assertThat(success(post(PLACE, Map.of("input", input), guest, null))
                        .at("/data/createGuestOrder/publicId")
                        .asString())
                .isEqualTo(order.at("/publicId").asString());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM checkout_requests WHERE request_id=?", Integer.class, requestId))
                .isEqualTo(1);
    }

    @Test
    void guestOrdersAreIsolatedAndMultipleOrdersKeepTheirOwnGrants() throws Exception {
        Session first = guest();
        UUID request = UUID.randomUUID();
        success(post(PLACE, Map.of("input", placement(first, request)), first, null));
        Session stranger = guest();
        placement(stranger, UUID.randomUUID());
        assertThat(post(CONFIRM, Map.of("id", request.toString()), stranger, null)
                        .body()
                        .at("/errors/0/extensions/errorType")
                        .asString())
                .isEqualTo("GUEST_ORDER_UNAVAILABLE");
        assertThat(post(CONFIRM, Map.of("id", request.toString()), new Session(), token())
                        .body()
                        .has("errors"))
                .isTrue();
        assertThat(post(CONFIRM, Map.of("id", request.toString()), new Session(), null)
                        .body()
                        .has("errors"))
                .isTrue();
        success(post("mutation{startGuestCart{cart{id}}}", Map.of(), first, null));
        success(post(
                "mutation($id:ID!){addGuestCartItem(input:{productVariantId:$id,quantity:1}){items{id}}}",
                Map.of("id", variant.getId().toString()),
                first,
                null));
        JsonNode preview = success(post(PREVIEW, Map.of("input", selections()), first, null))
                .at("/data/guestCheckoutPreview");
        UUID second = UUID.randomUUID();
        success(post(
                PLACE,
                Map.of(
                        "input",
                        Map.of(
                                "requestId",
                                second.toString(),
                                "acceptedQuoteVersion",
                                preview.at("/quoteVersion").asString(),
                                "checkout",
                                selections())),
                first,
                null));
        assertThat(success(post(CONFIRM, Map.of("id", request.toString()), first, null))
                        .at("/data/guestOrder/lines/0/quantity")
                        .asInt())
                .isEqualTo(2);
        assertThat(success(post(CONFIRM, Map.of("id", second.toString()), first, null))
                        .at("/data/guestOrder/lines/0/quantity")
                        .asInt())
                .isEqualTo(1);
    }

    @Test
    void authenticatedCheckoutDerivesOwnerAndPreservesScopes() throws Exception {
        carts.addItem(owner.getId(), variant.getId(), 1);
        String previewQuery = "query($input:CheckoutInput!){checkoutPreview(input:$input){quoteVersion totals{total}}}";
        var input = Map.of("shippingMethod", "PICKUP");
        Session noCookies = new Session();
        JsonNode preview = success(post(previewQuery, Map.of("input", input), noCookies, token()))
                .at("/data/checkoutPreview");
        UUID id = UUID.randomUUID();
        var placement = Map.of(
                "requestId",
                id.toString(),
                "acceptedQuoteVersion",
                preview.at("/quoteVersion").asString(),
                "checkout",
                input);
        String mutation =
                "mutation($input:PlaceOrderInput!){createOrder(input:$input){publicId status totalAmount checkout{totals{total} details{contactEmail}}}}";
        JsonNode order = success(post(mutation, Map.of("input", placement), noCookies, token()))
                .at("/data/createOrder");
        assertThat(order.at("/status").asString()).isEqualTo("PENDING");
        assertThat(order.at("/checkout/details/contactEmail").asString()).isEqualTo(owner.getEmail());
        assertThat(success(post(
                                "query($id:UUID!){checkoutOrder(requestId:$id){publicId}}",
                                Map.of("id", id.toString()),
                                noCookies,
                                token()))
                        .at("/data/checkoutOrder/publicId")
                        .asString())
                .isEqualTo(order.at("/publicId").asString());
        assertThat(success(post(
                                "{myOrders{checkout{requestId totals{total} lines{sku quantity}}}}",
                                Map.of(),
                                noCookies,
                                token()))
                        .at("/data/myOrders/0/checkout/requestId")
                        .asString())
                .isEqualTo(id.toString());
        assertThat(post(mutation, Map.of("input", placement), noCookies, owner.getId() + "/cart-read")
                        .body()
                        .has("errors"))
                .isTrue();
        assertThat(post("mutation{createOrder{id}}", Map.of(), noCookies, token())
                        .body()
                        .has("errors"))
                .isTrue();
        assertThat(post(previewQuery, Map.of("input", input), noCookies, "nonnumeric/cart-read+order-write")
                        .body()
                        .has("errors"))
                .isTrue();
    }

    @Test
    void guestCheckoutRequiresValidContactAndPreviewCookie() throws Exception {
        Session guest = guest();
        assertThat(post(PREVIEW, Map.of("input", Map.of("shippingMethod", "PICKUP")), guest, null)
                        .body()
                        .has("errors"))
                .isTrue();
        assertThat(post(
                                PREVIEW,
                                Map.of("input", Map.of("shippingMethod", "PICKUP", "contactEmail", "invalid")),
                                guest,
                                null)
                        .body()
                        .has("errors"))
                .isTrue();
        var input = placement(guest, UUID.randomUUID());
        guest.cookies.remove("retail_guest_orders");
        assertThat(post(PLACE, Map.of("input", input), guest, null).body().has("errors"))
                .isTrue();
        assertThat(guest.cookies.keySet()).contains("retail_guest_cart");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM checkout_requests WHERE request_id=?",
                        Integer.class,
                        UUID.fromString(input.get("requestId").toString())))
                .isZero();
    }

    @Test
    void anonymousRootGuardAndOriginProtectionsApplyToCheckout() throws Exception {
        Session guest = guest();
        var input = placement(guest, UUID.randomUUID());
        String mixed =
                "mutation($input:PlaceOrderInput!){createGuestOrder(input:$input){publicId} createOrder(input:$input){id}}";
        var mixedReply = post(mixed, Map.of("input", input), guest, null);
        assertThat(mixedReply.http().statusCode()).isEqualTo(subgraphRejectionStatus(401));
        assertThat(mixedReply.body().has("errors")).isTrue();
        var body = mapper.writeValueAsString(Map.of("query", PLACE, "variables", Map.of("input", input)));
        var request = HttpRequest.newBuilder(endpoint())
                .header("Content-Type", "application/json")
                .header("Origin", "http://evil.example")
                .header("X-Guest-Cart-Request", "1")
                .header("Cookie", guest.cookieHeader())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var foreignOrigin = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(foreignOrigin.statusCode()).isEqualTo(subgraphRejectionStatus(403));
        assertThat(mapper.readTree(foreignOrigin.body()).has("errors")).isTrue();
        var missingHeader = HttpRequest.newBuilder(endpoint())
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:3000")
                .header("Cookie", guest.cookieHeader())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var missingReply = client.send(missingHeader, HttpResponse.BodyHandlers.ofString());
        assertThat(missingReply.statusCode()).isEqualTo(subgraphRejectionStatus(403));
        assertThat(mapper.readTree(missingReply.body()).has("errors")).isTrue();
        var badBearer = post(PLACE, Map.of("input", input), guest, "bad");
        assertThat(badBearer.http().statusCode()).isEqualTo(subgraphRejectionStatus(401));
        if (badBearer.http().statusCode() == 200)
            assertThat(badBearer.body().has("errors")).isTrue();
        assertThat(badBearer.body().path("data").path("createGuestOrder").isObject())
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM checkout_requests WHERE request_id=?",
                        Integer.class,
                        UUID.fromString(input.get("requestId").toString())))
                .isZero();
        JsonNode options = success(post(
                        "{checkoutShippingOptions(countryCode:\"EE\"){method charge currency estimate pickupLocation{name}}}",
                        Map.of(),
                        guest,
                        null))
                .at("/data/checkoutShippingOptions");
        assertThat(options.size()).isEqualTo(3);
    }

    String token() {
        return owner.getId() + "/cart-read+cart-write+order-read+order-write";
    }

    int subgraphRejectionStatus(int springStatus) {
        return springStatus;
    }

    Reply post(String query, Map<String, ?> variables, Session session, String token) throws Exception {
        var builder = HttpRequest.newBuilder(endpoint())
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:3000")
                .header("X-Guest-Cart-Request", "1")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(Map.of("query", query, "variables", variables))));
        if (!session.cookies.isEmpty()) builder.header("Cookie", session.cookieHeader());
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        session.accept(response);
        return new Reply(mapper.readTree(response.body()), response);
    }

    JsonNode success(Reply reply) {
        assertThat(reply.http().statusCode()).isEqualTo(200);
        assertThat(reply.body().has("errors"))
                .as("GraphQL response: %s", reply.body())
                .isFalse();
        return reply.body();
    }
}
