package ee.bytecore.backend.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import ee.bytecore.backend.services.CartService;
import ee.bytecore.backend.services.OrderService;
import ee.bytecore.backend.services.ProductService;
import ee.bytecore.backend.services.ProductVariantService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
@Timeout(60)
class GuestCartGraphQlIntegrationTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String COOKIE_NAME = "retail_guest_cart";
    private static final String START =
            "mutation { startGuestCart { created cart { id expiresAt items { id } totals { subtotal currency } } } }";
    private static final String READ =
            """
            query {
              guestCart {
                id expiresAt totals { subtotal currency }
                items {
                  id quantity subtotal
                  productVariant { id sku price isActive product { id name slug } }
                }
              }
            }
            """;
    private static final String MERGE =
            """
            mutation($id: UUID!) {
              mergeGuestCart(requestId: $id) {
                status
                cart { id totals { subtotal currency } items { quantity productVariant { id } } }
                mergedItems { productVariantId userQuantityBefore guestQuantityAdded finalQuantity }
                conflicts { productVariantId code userQuantity guestQuantity message }
              }
            }
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
    CartService carts;

    @Autowired
    OrderService orders;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PaymentOutboxPublisher outboxPublisher;

    private final HttpClient client = HttpClient.newHttpClient();
    private User owner;
    private ProductVariant variant;

    private record Reply(int status, JsonNode body, List<String> cookies) {}

    private record Guest(String cookie, long cartId) {}

    @BeforeEach
    void setUp() {
        owner = user();
        variant = variant("19.99");
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            String[] parts = token.split("/", -1);
            if (parts.length != 2) {
                throw new org.springframework.security.oauth2.jwt.BadJwtException("Invalid test bearer");
            }
            return Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject(parts[0])
                    .claim("role", "USER")
                    .claim("scope", parts[1].replace("-", ":").replace("+", " "))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(120))
                    .build();
        });
    }

    @Test
    void shouldCreateAndRetrievePersistedGuestCartWithoutExposingCredentialTest() throws Exception {
        Reply start = success(request(START, Map.of(), null, null));
        JsonNode cart = start.body().at("/data/startGuestCart/cart");
        assertThat(start.body().at("/data/startGuestCart/created").asBoolean()).isTrue();
        assertThat(cart.at("/items").size()).isZero();
        assertAmount(cart.at("/totals/subtotal"), "0.00");
        assertThat(cart.at("/totals/currency").asString()).isEqualTo("EUR");
        Instant expiresAt = Instant.parse(cart.at("/expiresAt").asString());
        assertThat(expiresAt).isAfter(Instant.now().plusSeconds(29 * 24 * 3600));
        assertThat(expiresAt).isBefore(Instant.now().plusSeconds(31 * 24 * 3600));
        HttpCookie cookie = credential(start);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/graphql");
        assertThat(cookie.getMaxAge()).isPositive();
        assertThat(start.cookies().stream().anyMatch(value -> value.contains("SameSite=Lax")))
                .isTrue();
        assertThat(cookie.getSecure()).as("local HTTP dev profile").isFalse();
        String transport = COOKIE_NAME + "=" + cookie.getValue();
        Reply read = success(request(READ, Map.of(), transport, null));
        assertThat(read.body().at("/data/guestCart/id").asString())
                .isEqualTo(cart.at("/id").asString());
        assertThat(read.body().at("/data/guestCart/expiresAt").asString())
                .isEqualTo(cart.at("/expiresAt").asString());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM carts WHERE id=? AND user_id IS NULL",
                        Integer.class,
                        Long.valueOf(cart.at("/id").asString())))
                .isEqualTo(1);
        assertThat(read.body().toString().contains(cookie.getValue()))
                .as("credential is not in cart JSON")
                .isFalse();
        assertThat(jdbc.queryForObject(
                                "SELECT guest_credential_hash FROM carts WHERE id=?",
                                String.class,
                                Long.valueOf(cart.at("/id").asString()))
                        .equals(cookie.getValue()))
                .as("only a credential hash is persisted")
                .isFalse();
        Reply repeat = success(request(START, Map.of(), transport, null));
        assertThat(repeat.body().at("/data/startGuestCart/created").asBoolean()).isFalse();
        assertThat(repeat.body().at("/data/startGuestCart/cart/id").asString())
                .isEqualTo(cart.at("/id").asString());
    }

    @Test
    void shouldReturnNullWithoutCreatingCartWhenGuestCookieIsMissingTest() throws Exception {
        Reply read = success(request(READ, Map.of(), null, null));
        assertThat(read.body().at("/data/guestCart").isNull()).isTrue();
        assertThat(read.cookies()).isEmpty();
    }

    @Test
    void shouldAddCombineUpdateRemoveAndCalculateCurrentPricesTest() throws Exception {
        Guest guest = start();
        JsonNode added = add(guest, variant, 2);
        assertAmount(added.at("/totals/subtotal"), "39.98");
        assertAmount(added.at("/items/0/subtotal"), "39.98");
        assertThat(added.at("/items/0/productVariant/product/name").asString()).startsWith("Guest fixture");
        add(guest, variant, 3);
        JsonNode read = read(guest);
        assertThat(read.at("/items").size()).isEqualTo(1);
        assertThat(read.at("/items/0/quantity").asInt()).isEqualTo(5);
        assertAmount(read.at("/totals/subtotal"), "99.95");
        String itemId = read.at("/items/0/id").asString();
        success(request(
                "mutation($i:ID!){updateGuestCartItem(cartItemId:$i,input:{quantity:3}){items{id quantity} totals{subtotal}}}",
                Map.of("i", itemId),
                guest.cookie(),
                null));
        variants.update(variant.getId(), null, new BigDecimal("10.01"), null, null, null, null);
        assertAmount(read(guest).at("/totals/subtotal"), "30.03");
        Reply removed = success(request(
                "mutation($i:ID!){removeGuestCartItem(cartItemId:$i){items{id} totals{subtotal}}}",
                Map.of("i", itemId),
                guest.cookie(),
                null));
        assertThat(removed.body().at("/data/removeGuestCartItem/items").size()).isZero();
        assertAmount(removed.body().at("/data/removeGuestCartItem/totals/subtotal"), "0.00");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM cart_items WHERE cart_id=?", Integer.class, guest.cartId()))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void shouldRejectInvalidQuantityWithoutChangingGuestCartTest(int quantity) throws Exception {
        Guest guest = start();
        add(guest, variant, 2);
        denied(request(
                "mutation($v:ID!,$q:Int!){addGuestCartItem(input:{productVariantId:$v,quantity:$q}){id}}",
                Map.of("v", variant.getId().toString(), "q", quantity),
                guest.cookie(),
                null));
        String itemId = read(guest).at("/items/0/id").asString();
        denied(request(
                "mutation($i:ID!,$q:Int!){updateGuestCartItem(cartItemId:$i,input:{quantity:$q}){id}}",
                Map.of("i", itemId, "q", quantity),
                guest.cookie(),
                null));
        assertThat(read(guest).at("/items/0/quantity").asInt()).isEqualTo(2);
    }

    @Test
    void shouldRejectOverflowWithoutWrappingPersistedQuantityTest() throws Exception {
        Guest guest = start();
        add(guest, variant, Integer.MAX_VALUE);
        denied(request(
                "mutation($v:ID!){addGuestCartItem(input:{productVariantId:$v,quantity:1}){id}}",
                Map.of("v", variant.getId().toString()),
                guest.cookie(),
                null));
        assertThat(read(guest).at("/items/0/quantity").asInt()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void shouldRejectInactiveVariantForAddAndUpdateButAllowRemovalTest() throws Exception {
        Guest guest = start();
        add(guest, variant, 2);
        String itemId = read(guest).at("/items/0/id").asString();
        variants.update(variant.getId(), null, null, null, null, null, false);
        denied(request(
                "mutation($v:ID!){addGuestCartItem(input:{productVariantId:$v,quantity:1}){id}}",
                Map.of("v", variant.getId().toString()),
                guest.cookie(),
                null));
        denied(request(
                "mutation($i:ID!){updateGuestCartItem(cartItemId:$i,input:{quantity:3}){id}}",
                Map.of("i", itemId),
                guest.cookie(),
                null));
        assertThat(read(guest).at("/items/0/productVariant/isActive").asBoolean())
                .isFalse();
        success(request(
                "mutation($i:ID!){removeGuestCartItem(cartItemId:$i){id}}", Map.of("i", itemId), guest.cookie(), null));
    }

    @Test
    void shouldRejectMissingVariantAndNullQuantityWithoutCreatingItemsTest() throws Exception {
        Guest guest = start();
        denied(request(
                "mutation{addGuestCartItem(input:{productVariantId:\"9223372036854775807\",quantity:1}){id}}",
                Map.of(),
                guest.cookie(),
                null));
        denied(request(
                "mutation($v:ID!){addGuestCartItem(input:{productVariantId:$v,quantity:null}){id}}",
                Map.of("v", variant.getId().toString()),
                guest.cookie(),
                null));
        assertThat(read(guest).at("/items").size()).isZero();
    }

    @Test
    void shouldNotCheckOrReserveInventoryForGuestCrudOrMergeTest() throws Exception {
        Guest guest = start();
        add(guest, variant, 7);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory WHERE product_variant_id=?", Integer.class, variant.getId()))
                .isZero();
        assertThat(merge(guest, UUID.randomUUID(), owner).at("/status").asString())
                .isEqualTo("MERGED");
        assertThat(userQuantity(owner, variant)).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inventory WHERE product_variant_id=?", Integer.class, variant.getId()))
                .isZero();
    }

    @Test
    void shouldLeaveInsufficientStockUnchangedWhenGuestQuantitiesMergeTest() throws Exception {
        Long warehouse = jdbc.queryForObject(
                "INSERT INTO warehouses(name) VALUES (?) RETURNING id", Long.class, "Guest stock " + UUID.randomUUID());
        jdbc.update(
                "INSERT INTO inventory(product_variant_id,warehouse_id,quantity) VALUES (?,?,5)",
                variant.getId(),
                warehouse);
        Guest guest = start();
        add(guest, variant, 7);
        assertThat(merge(guest, UUID.randomUUID(), owner).at("/status").asString())
                .isEqualTo("MERGED");
        assertThat(userQuantity(owner, variant)).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                        "SELECT quantity FROM inventory WHERE product_variant_id=? AND warehouse_id=?",
                        Integer.class,
                        variant.getId(),
                        warehouse))
                .isEqualTo(5);
    }

    @Test
    void shouldDenyForeignGuestAndAuthenticatedCartItemsTest() throws Exception {
        Guest first = start();
        Guest second = start();
        add(first, variant, 2);
        String foreignId = read(first).at("/items/0/id").asString();
        var ownedItem = carts.addItem(owner.getId(), variant.getId(), 3);
        for (String itemId : List.of(foreignId, ownedItem.getId().toString())) {
            denied(request(
                    "mutation($i:ID!){updateGuestCartItem(cartItemId:$i,input:{quantity:9}){id}}",
                    Map.of("i", itemId),
                    second.cookie(),
                    null));
            denied(request(
                    "mutation($i:ID!){removeGuestCartItem(cartItemId:$i){id}}",
                    Map.of("i", itemId),
                    second.cookie(),
                    null));
        }
        assertThat(read(second).at("/items").size()).isZero();
        assertThat(read(first).at("/items/0/quantity").asInt()).isEqualTo(2);
        assertThat(userQuantity(owner, variant)).isEqualTo(3);
    }

    @Test
    void shouldNotUsePublicCartIdAsCredentialTest() throws Exception {
        Guest guest = start();
        denied(request(READ, Map.of(), COOKIE_NAME + "=" + guest.cartId(), null));
        Reply withoutCredential = success(request(
                READ,
                Map.of("cartId", guest.cartId()),
                null,
                null,
                ORIGIN,
                true,
                Map.of("X-Guest-Cart-Id", Long.toString(guest.cartId()))));
        assertThat(withoutCredential.body().at("/data/guestCart").isNull()).isTrue();
    }

    @Test
    void shouldDenyInvalidExpiredAndDeletedGuestCredentialsTest() throws Exception {
        denied(request(READ, Map.of(), COOKIE_NAME + "=malformed", null));
        Guest guest = start();
        add(guest, variant, 1);
        jdbc.update("UPDATE carts SET guest_expires_at=NOW()-INTERVAL '1 second' WHERE id=?", guest.cartId());
        unavailable(request(READ, Map.of(), guest.cookie(), null));
        unavailable(request(MERGE, Map.of("id", UUID.randomUUID().toString()), guest.cookie(), token(owner)));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM cart_items WHERE cart_id=?", Integer.class, guest.cartId()))
                .isEqualTo(1);
        jdbc.update("DELETE FROM carts WHERE id=?", guest.cartId());
        unavailable(request(READ, Map.of(), guest.cookie(), null));
        Reply replacement = success(request(START, Map.of(), guest.cookie(), null));
        assertThat(replacement.body().at("/data/startGuestCart/created").asBoolean())
                .isTrue();
        assertThat(replacement.body().at("/data/startGuestCart/cart/items").size())
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "https://evil.example", "http://localhost:3000.evil.example"})
    void shouldRejectMissingOrUntrustedOriginForCookieOperationsTest(String origin) throws Exception {
        Guest guest = start();
        Reply startDenied = request(START, Map.of(), null, null, origin, true, Map.of());
        assertThat(startDenied.status()).isEqualTo(403);
        assertThat(startDenied.cookies()).isEmpty();
        assertThat(request(READ, Map.of(), guest.cookie(), null, origin, true, Map.of())
                        .status())
                .isEqualTo(403);
        assertThat(request(
                                MERGE,
                                Map.of("id", UUID.randomUUID().toString()),
                                guest.cookie(),
                                token(owner),
                                origin,
                                true,
                                Map.of())
                        .status())
                .isEqualTo(403);
        assertThat(read(guest).at("/id").asString()).isEqualTo(Long.toString(guest.cartId()));
    }

    @Test
    void shouldRequireCustomHeaderForInitialCreationAndMergeTest() throws Exception {
        assertThat(request(START, Map.of(), null, null, ORIGIN, false, Map.of()).status())
                .isEqualTo(403);
        Guest guest = start();
        assertThat(request(
                                MERGE,
                                Map.of("id", UUID.randomUUID().toString()),
                                guest.cookie(),
                                token(owner),
                                ORIGIN,
                                false,
                                Map.of())
                        .status())
                .isEqualTo(403);
        assertThat(read(guest).at("/id").asString()).isEqualTo(Long.toString(guest.cartId()));
    }

    @Test
    void shouldRejectProtectedRootsMixedOperationsAndFederationThroughGuestAccessTest() throws Exception {
        Guest guest = start();
        for (String query : List.of(
                "{ myCart { id } }",
                "{ me { id } }",
                "{ _service { sdl } }",
                "{ __schema { queryType { name } } }",
                "{ allowed:guestCart{id} protected:myCart{id} }",
                "query Selected { ...Protected } fragment Protected on Query { myCart { id } }",
                "mutation { clearCart }")) {
            denied(request(query, Map.of(), guest.cookie(), null));
        }
        Reply allowed = success(request(
                "query Selected { alias:guestCart { ...Details } } fragment Details on GuestCart { id }",
                Map.of(),
                guest.cookie(),
                null));
        assertThat(allowed.body().at("/data/alias/id").asString()).isEqualTo(Long.toString(guest.cartId()));
        add(guest, variant, 1);
        denied(request("{guestCart{items{productVariant{inventory{quantity}}}}}", Map.of(), guest.cookie(), null));
        denied(request("{guestCart{user{id}}}", Map.of(), guest.cookie(), null));
        assertThat(read(guest).at("/items/0/quantity").asInt()).isEqualTo(1);
        denied(request(READ, Map.of(), guest.cookie(), "invalid-test-bearer"));
        int cartsBefore = jdbc.queryForObject("SELECT count(*) FROM carts WHERE user_id IS NULL", Integer.class);
        denied(request("mutation{allowed:startGuestCart{cart{id}} protected:clearCart}", Map.of(), null, null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE user_id IS NULL", Integer.class))
                .as("mixed operation is rejected before public mutation side effects")
                .isEqualTo(cartsBefore);
    }

    @Test
    void shouldPreserveAuthenticatedCartScopesAndOwnershipTest() throws Exception {
        Guest guest = start();
        var item = carts.addItem(owner.getId(), variant.getId(), 2);
        success(request("{myCart{items{quantity}}}", Map.of(), guest.cookie(), token(owner), "", false, Map.of()));
        denied(request("{myCart{id}}", Map.of(), guest.cookie(), owner.getId() + "/cart-write"));
        denied(request(
                MERGE, Map.of("id", UUID.randomUUID().toString()), guest.cookie(), owner.getId() + "/cart-read"));
        denied(request(
                MERGE, Map.of("id", UUID.randomUUID().toString()), guest.cookie(), owner.getId() + "/cart-write"));
        denied(request(
                MERGE, Map.of("id", UUID.randomUUID().toString()), guest.cookie(), "machine/cart-read+cart-write"));
        denied(request(MERGE, Map.of("id", UUID.randomUUID().toString()), guest.cookie(), null));
        denied(request(
                "mutation($i:ID!){removeCartItem(cartItemId:$i)}",
                Map.of("i", item.getId().toString()),
                guest.cookie(),
                token(user())));
        assertThat(userQuantity(owner, variant)).isEqualTo(2);
    }

    @Test
    void shouldMergeOverlappingAndNewVariantsAndReplayWithoutCookieTest() throws Exception {
        Guest guest = start();
        ProductVariant other = variant("5.01");
        carts.addItem(owner.getId(), variant.getId(), 3);
        add(guest, variant, 2);
        add(guest, other, 4);
        UUID requestId = UUID.randomUUID();
        Reply merged = success(request(MERGE, Map.of("id", requestId.toString()), guest.cookie(), token(owner)));
        JsonNode result = merged.body().at("/data/mergeGuestCart");
        assertThat(result.at("/status").asString()).isEqualTo("MERGED");
        assertThat(result.at("/mergedItems").size()).isEqualTo(2);
        for (JsonNode summary : result.at("/mergedItems")) {
            boolean overlapping = variant.getId()
                    .toString()
                    .equals(summary.at("/productVariantId").asString());
            assertThat(summary.at("/userQuantityBefore").asInt()).isEqualTo(overlapping ? 3 : 0);
            assertThat(summary.at("/guestQuantityAdded").asInt()).isEqualTo(overlapping ? 2 : 4);
            assertThat(summary.at("/finalQuantity").asInt()).isEqualTo(overlapping ? 5 : 4);
        }
        assertThat(result.at("/conflicts").size()).isZero();
        assertAmount(result.at("/cart/totals/subtotal"), "119.99");
        assertThat(merged.cookies().stream()
                        .flatMap(value -> HttpCookie.parse(value).stream())
                        .anyMatch(cookie -> COOKIE_NAME.equals(cookie.getName()) && cookie.getMaxAge() == 0))
                .as("successful merge clears the guest cookie")
                .isTrue();
        assertThat(userQuantity(owner, variant)).isEqualTo(5);
        assertThat(userQuantity(owner, other)).isEqualTo(4);
        JsonNode replay = merge(null, requestId, owner);
        assertThat(replay.at("/status").asString()).isEqualTo("REPLAYED");
        assertThat(replay.at("/mergedItems")).isEqualTo(result.at("/mergedItems"));
        assertThat(userQuantity(owner, variant)).isEqualTo(5);
        unavailable(request(READ, Map.of(), guest.cookie(), null));
        unavailable(request(MERGE, Map.of("id", UUID.randomUUID().toString()), guest.cookie(), token(owner)));
    }

    @Test
    void shouldReportAllConflictsAndPreserveBothCartsUntilResolvedTest() throws Exception {
        Guest guest = start();
        ProductVariant inactive = variant("2.00");
        ProductVariant overflow = variant("1.00");
        carts.addItem(owner.getId(), variant.getId(), 3);
        carts.addItem(owner.getId(), overflow.getId(), Integer.MAX_VALUE);
        add(guest, variant, 2);
        add(guest, inactive, 1);
        add(guest, overflow, 1);
        variants.update(inactive.getId(), null, null, null, null, null, false);
        UUID requestId = UUID.randomUUID();
        JsonNode result = merge(guest, requestId, owner);
        assertThat(result.at("/status").asString()).isEqualTo("BLOCKED");
        assertThat(result.at("/conflicts").size()).isEqualTo(2);
        assertThat(result.at("/mergedItems").size()).isZero();
        assertThat(result.at("/conflicts").toString()).contains("VARIANT_UNAVAILABLE", "QUANTITY_OVERFLOW");
        assertThat(userQuantity(owner, variant)).isEqualTo(3);
        assertThat(userQuantity(owner, overflow)).isEqualTo(Integer.MAX_VALUE);
        assertThat(read(guest).at("/items").size()).isEqualTo(3);
        for (JsonNode item : read(guest).at("/items")) {
            if (!item.at("/productVariant/id").asString().equals(variant.getId().toString())) {
                success(request(
                        "mutation($i:ID!){removeGuestCartItem(cartItemId:$i){id}}",
                        Map.of("i", item.at("/id").asString()),
                        guest.cookie(),
                        null));
            }
        }
        assertThat(merge(guest, requestId, owner).at("/status").asString()).isEqualTo("MERGED");
        assertThat(userQuantity(owner, variant)).isEqualTo(5);
    }

    @Test
    void shouldRejectReceiptReuseForDifferentGuestAndDifferentOwnerTest() throws Exception {
        Guest first = start();
        add(first, variant, 2);
        UUID requestId = UUID.randomUUID();
        merge(first, requestId, owner);
        Guest second = start();
        add(second, variant, 4);
        denied(request(MERGE, Map.of("id", requestId.toString()), second.cookie(), token(owner)));
        denied(request(MERGE, Map.of("id", requestId.toString()), null, token(user())));
        assertThat(userQuantity(owner, variant)).isEqualTo(2);
        assertThat(read(second).at("/items/0/quantity").asInt()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRejectInactiveAndDeletedOwnersWithoutConsumingGuestTest(boolean deleted) throws Exception {
        Guest guest = start();
        add(guest, variant, 2);
        jdbc.update(
                "UPDATE users SET deleted=?,deletion_identity_id=? WHERE id=?",
                deleted,
                UUID.randomUUID(),
                owner.getId());
        denied(request(MERGE, Map.of("id", UUID.randomUUID().toString()), guest.cookie(), token(owner)));
        assertThat(read(guest).at("/items/0/quantity").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE user_id=?", Integer.class, owner.getId()))
                .isZero();
    }

    @Test
    void shouldRecheckActiveAccountForReceiptReplayTest() throws Exception {
        Guest guest = start();
        add(guest, variant, 1);
        UUID requestId = UUID.randomUUID();
        merge(guest, requestId, owner);
        jdbc.update("UPDATE users SET deletion_identity_id=? WHERE id=?", UUID.randomUUID(), owner.getId());
        denied(request(MERGE, Map.of("id", requestId.toString()), null, token(owner)));
    }

    @Test
    void shouldMergeEmptyGuestWithoutClearingExistingUserItemsTest() throws Exception {
        Guest guest = start();
        carts.addItem(owner.getId(), variant.getId(), 3);
        JsonNode result = merge(guest, UUID.randomUUID(), owner);
        assertThat(result.at("/status").asString()).isEqualTo("MERGED");
        assertThat(result.at("/mergedItems").size()).isZero();
        assertThat(userQuantity(owner, variant)).isEqualTo(3);
        unavailable(request(READ, Map.of(), guest.cookie(), null));
    }

    @Test
    void shouldPreserveExistingUnavailableUserLinesNotPresentInGuestTest() throws Exception {
        Guest guest = start();
        ProductVariant existing = variant("2.00");
        carts.addItem(owner.getId(), existing.getId(), 3);
        variants.update(existing.getId(), null, null, null, null, null, false);
        add(guest, variant, 2);
        assertThat(merge(guest, UUID.randomUUID(), owner).at("/status").asString())
                .isEqualTo("MERGED");
        assertThat(userQuantity(owner, existing)).isEqualTo(3);
        assertThat(userQuantity(owner, variant)).isEqualTo(2);
    }

    @Test
    void shouldNotCreateDestinationWhenFirstMergeIsBlockedTest() throws Exception {
        Guest guest = start();
        add(guest, variant, 2);
        variants.update(variant.getId(), null, null, null, null, null, false);
        JsonNode result = merge(guest, UUID.randomUUID(), owner);
        assertThat(result.at("/status").asString()).isEqualTo("BLOCKED");
        assertThat(result.at("/cart").isNull()).isTrue();
        assertThat(result.at("/conflicts").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE user_id=?", Integer.class, owner.getId()))
                .isZero();
        assertThat(read(guest).at("/items/0/quantity").asInt()).isEqualTo(2);
    }

    @Test
    void shouldSerializeMergeWithSimultaneousAuthenticatedCartUpdateTest() throws Exception {
        Guest guest = start();
        carts.addItem(owner.getId(), variant.getId(), 3);
        add(guest, variant, 2);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var merged = executor.submit(() -> concurrentMerge(guest, UUID.randomUUID(), ready, go));
            var added = executor.submit(() -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Cart update start timed out");
                return carts.addItem(owner.getId(), variant.getId(), 1);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(success(merged.get(20, TimeUnit.SECONDS))
                            .body()
                            .at("/data/mergeGuestCart/status")
                            .asString())
                    .isEqualTo("MERGED");
            added.get(20, TimeUnit.SECONDS);
            assertThat(userQuantity(owner, variant)).isEqualTo(6);
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldSerializeMergeWithSimultaneousGuestQuantityUpdateTest() throws Exception {
        Guest guest = start();
        carts.addItem(owner.getId(), variant.getId(), 3);
        add(guest, variant, 2);
        String itemId = read(guest).at("/items/0/id").asString();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var merged = executor.submit(() -> concurrentMerge(guest, UUID.randomUUID(), ready, go));
            var updated = executor.submit(() -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Guest update start timed out");
                return request(
                        "mutation($i:ID!){updateGuestCartItem(cartItemId:$i,input:{quantity:4}){id}}",
                        Map.of("i", itemId),
                        guest.cookie(),
                        null);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(success(merged.get(20, TimeUnit.SECONDS))
                            .body()
                            .at("/data/mergeGuestCart/status")
                            .asString())
                    .isEqualTo("MERGED");
            Reply update = updated.get(20, TimeUnit.SECONDS);
            if (update.status() == 200 && !update.body().has("errors")) {
                assertThat(userQuantity(owner, variant)).isEqualTo(7);
            } else {
                denied(update);
                assertThat(userQuantity(owner, variant)).isEqualTo(5);
            }
            unavailable(request(READ, Map.of(), guest.cookie(), null));
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldSerializeMergeWithCheckoutWithoutLosingOrDuplicatingGuestItemsTest() throws Exception {
        Guest guest = start();
        carts.addItem(owner.getId(), variant.getId(), 3);
        add(guest, variant, 2);
        Long warehouse = jdbc.queryForObject(
                "INSERT INTO warehouses(name) VALUES (?) RETURNING id",
                Long.class,
                "Guest checkout " + UUID.randomUUID());
        jdbc.update(
                "INSERT INTO inventory(product_variant_id,warehouse_id,quantity) VALUES (?,?,10)",
                variant.getId(),
                warehouse);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var merged = executor.submit(() -> concurrentMerge(guest, UUID.randomUUID(), ready, go));
            var checkout = executor.submit(() -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Checkout start timed out");
                return orders.createOrder(owner.getId());
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(success(merged.get(20, TimeUnit.SECONDS))
                            .body()
                            .at("/data/mergeGuestCart/status")
                            .asString())
                    .isEqualTo("MERGED");
            checkout.get(20, TimeUnit.SECONDS);
            int ordered = jdbc.queryForObject(
                    """
                    SELECT sum(i.quantity) FROM order_items i JOIN orders o ON o.id=i.order_id
                    WHERE o.user_id=? AND i.product_variant_id=?
                    """,
                    Integer.class,
                    owner.getId(),
                    variant.getId());
            int remaining = jdbc.queryForObject(
                    """
                    SELECT COALESCE(sum(i.quantity),0) FROM cart_items i JOIN carts c ON c.id=i.cart_id
                    WHERE c.user_id=? AND i.product_variant_id=?
                    """,
                    Integer.class,
                    owner.getId(),
                    variant.getId());
            assertThat(ordered).isIn(3, 5);
            assertThat(ordered + remaining).isEqualTo(5);
            assertThat(jdbc.queryForObject(
                            "SELECT quantity FROM inventory WHERE product_variant_id=? AND warehouse_id=?",
                            Integer.class,
                            variant.getId(),
                            warehouse))
                    .isEqualTo(10 - ordered);
            unavailable(request(READ, Map.of(), guest.cookie(), null));
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldNotMergeOneGuestIntoTwoConcurrentUsersTest() throws Exception {
        Guest guest = start();
        add(guest, variant, 2);
        User other = user();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> concurrentMerge(guest, UUID.randomUUID(), ready, go));
            var second = executor.submit(() -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("Second owner merge start timed out");
                return request(MERGE, Map.of("id", UUID.randomUUID().toString()), guest.cookie(), token(other));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Reply> replies = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(replies.stream()
                            .filter(reply -> "MERGED"
                                    .equals(reply.body()
                                            .at("/data/mergeGuestCart/status")
                                            .asString()))
                            .count())
                    .isEqualTo(1);
            denied(replies.stream()
                    .filter(reply -> !"MERGED"
                            .equals(reply.body()
                                    .at("/data/mergeGuestCart/status")
                                    .asString()))
                    .findFirst()
                    .orElseThrow());
            assertThat(jdbc.queryForObject(
                            """
                    SELECT COALESCE(sum(i.quantity),0) FROM cart_items i JOIN carts c ON c.id=i.cart_id
                    WHERE c.user_id IN (?,?) AND i.product_variant_id=?
                    """,
                            Integer.class,
                            owner.getId(),
                            other.getId(),
                            variant.getId()))
                    .isEqualTo(2);
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldApplyConcurrentMergeOnlyOnceTest(boolean sameRequestId) throws Exception {
        Guest guest = start();
        carts.addItem(owner.getId(), variant.getId(), 3);
        add(guest, variant, 2);
        UUID firstId = UUID.randomUUID();
        UUID secondId = sameRequestId ? firstId : UUID.randomUUID();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> concurrentMerge(guest, firstId, ready, go));
            var second = executor.submit(() -> concurrentMerge(guest, secondId, ready, go));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Reply> replies = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(replies.stream()
                            .filter(reply -> "MERGED"
                                    .equals(reply.body()
                                            .at("/data/mergeGuestCart/status")
                                            .asString()))
                            .count())
                    .isEqualTo(1);
            if (sameRequestId) {
                assertThat(replies.stream()
                                .filter(reply -> "REPLAYED"
                                        .equals(reply.body()
                                                .at("/data/mergeGuestCart/status")
                                                .asString()))
                                .count())
                        .isEqualTo(1);
            } else {
                denied(replies.stream()
                        .filter(reply -> !"MERGED"
                                .equals(reply.body()
                                        .at("/data/mergeGuestCart/status")
                                        .asString()))
                        .findFirst()
                        .orElseThrow());
            }
            assertThat(userQuantity(owner, variant)).isEqualTo(5);
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRollBackDestinationAndPreserveSourceOnDatabaseFailureTest() throws Exception {
        Guest guest = start();
        ProductVariant failing = variant("4.00");
        carts.addItem(owner.getId(), variant.getId(), 3);
        add(guest, variant, 2);
        add(guest, failing, 1);
        String name = "guest_rollback_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                """
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.product_variant_id=%d AND EXISTS (
                    SELECT 1 FROM carts WHERE id=NEW.cart_id AND user_id=%d
                  ) THEN
                    RAISE EXCEPTION 'Guest merge rollback fixture';
                  END IF;
                  RETURN NEW;
                END $$;
                """
                        .formatted(name, failing.getId(), owner.getId()));
        jdbc.execute("CREATE TRIGGER " + name + " BEFORE INSERT OR UPDATE ON cart_items FOR EACH ROW EXECUTE FUNCTION "
                + name + "()");
        UUID requestId = UUID.randomUUID();
        try {
            Reply failed = request(MERGE, Map.of("id", requestId.toString()), guest.cookie(), token(owner));
            denied(failed);
            assertThat(failed.cookies().stream()
                            .flatMap(value -> HttpCookie.parse(value).stream())
                            .anyMatch(cookie -> COOKIE_NAME.equals(cookie.getName()) && cookie.getMaxAge() == 0))
                    .as("failed transaction does not clear credential")
                    .isFalse();
            assertThat(userQuantity(owner, variant)).isEqualTo(3);
            assertThat(read(guest).at("/items").size()).isEqualTo(2);
        } finally {
            jdbc.execute("DROP TRIGGER " + name + " ON cart_items");
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
        assertThat(merge(guest, requestId, owner).at("/status").asString()).isEqualTo("MERGED");
        assertThat(userQuantity(owner, variant)).isEqualTo(5);
        assertThat(userQuantity(owner, failing)).isEqualTo(1);
    }

    @Test
    void shouldRollBackNewDestinationCreationOnDatabaseFailureTest() throws Exception {
        Guest guest = start();
        add(guest, variant, 2);
        String name = "guest_new_cart_rollback_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute(
                """
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.product_variant_id=%d AND EXISTS (
                    SELECT 1 FROM carts WHERE id=NEW.cart_id AND user_id=%d
                  ) THEN
                    RAISE EXCEPTION 'Guest first merge rollback fixture';
                  END IF;
                  RETURN NEW;
                END $$;
                """
                        .formatted(name, variant.getId(), owner.getId()));
        jdbc.execute(
                "CREATE TRIGGER " + name + " BEFORE INSERT ON cart_items FOR EACH ROW EXECUTE FUNCTION " + name + "()");
        UUID requestId = UUID.randomUUID();
        try {
            denied(request(MERGE, Map.of("id", requestId.toString()), guest.cookie(), token(owner)));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE user_id=?", Integer.class, owner.getId()))
                    .as("target creation participates in merge transaction")
                    .isZero();
            assertThat(read(guest).at("/items/0/quantity").asInt()).isEqualTo(2);
        } finally {
            jdbc.execute("DROP TRIGGER " + name + " ON cart_items");
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
        assertThat(merge(guest, requestId, owner).at("/status").asString()).isEqualTo("MERGED");
    }

    private Reply concurrentMerge(Guest guest, UUID requestId, CountDownLatch ready, CountDownLatch go)
            throws Exception {
        ready.countDown();
        if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent merge start timed out");
        return request(MERGE, Map.of("id", requestId.toString()), guest.cookie(), token(owner));
    }

    private Guest start() throws Exception {
        Reply reply = success(request(START, Map.of(), null, null));
        HttpCookie cookie = credential(reply);
        return new Guest(
                COOKIE_NAME + "=" + cookie.getValue(),
                Long.parseLong(reply.body().at("/data/startGuestCart/cart/id").asString()));
    }

    private HttpCookie credential(Reply reply) {
        return reply.cookies().stream()
                .flatMap(value -> HttpCookie.parse(value).stream())
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Guest credential cookie is missing"));
    }

    private JsonNode read(Guest guest) throws Exception {
        return success(request(READ, Map.of(), guest.cookie(), null)).body().at("/data/guestCart");
    }

    private JsonNode add(Guest guest, ProductVariant productVariant, int quantity) throws Exception {
        return success(request(
                        """
                mutation($v:ID!,$q:Int!){
                  addGuestCartItem(input:{productVariantId:$v,quantity:$q}){
                    id totals{subtotal currency}
                    items{id quantity subtotal productVariant{id product{id name slug}}}
                  }
                }
                """,
                        Map.of("v", productVariant.getId().toString(), "q", quantity),
                        guest.cookie(),
                        null))
                .body()
                .at("/data/addGuestCartItem");
    }

    private JsonNode merge(Guest guest, UUID requestId, User user) throws Exception {
        return success(request(
                        MERGE, Map.of("id", requestId.toString()), guest == null ? null : guest.cookie(), token(user)))
                .body()
                .at("/data/mergeGuestCart");
    }

    private User user() {
        String suffix = UUID.randomUUID().toString();
        return users.save(User.create("guest-user-" + suffix, suffix + "@example.com", LocalDate.of(1990, 1, 1)));
    }

    private ProductVariant variant(String price) {
        String suffix = UUID.randomUUID().toString();
        var product = products.create("Guest fixture " + suffix, "guest-product-" + suffix, null, null);
        return variants.create(product.getId(), "GUEST-" + suffix, new BigDecimal(price), null, null, null);
    }

    private int userQuantity(User user, ProductVariant productVariant) {
        return jdbc.queryForObject(
                """
                SELECT i.quantity FROM cart_items i JOIN carts c ON c.id=i.cart_id
                WHERE c.user_id=? AND i.product_variant_id=?
                """,
                Integer.class,
                user.getId(),
                productVariant.getId());
    }

    private String token(User user) {
        return user.getId() + "/cart-read+cart-write";
    }

    private Reply request(String query, Map<String, Object> variables, String cookie, String token) throws Exception {
        return request(query, variables, cookie, token, ORIGIN, true, Map.of());
    }

    private Reply request(
            String query,
            Map<String, Object> variables,
            String cookie,
            String token,
            String origin,
            boolean guestHeader,
            Map<String, String> extraHeaders)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(Map.of("query", query, "variables", variables))));
        if (!origin.isEmpty()) builder.header("Origin", origin);
        if (guestHeader) builder.header("X-Guest-Cart-Request", "1");
        if (cookie != null) builder.header("Cookie", cookie);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        extraHeaders.forEach(builder::header);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
        return new Reply(response.statusCode(), body, response.headers().allValues("Set-Cookie"));
    }

    private Reply success(Reply reply) {
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().has("errors"))
                .as("GraphQL response: %s", reply.body())
                .isFalse();
        return reply;
    }

    private void denied(Reply reply) {
        assertThat(reply.status() >= 400 || reply.body().has("errors"))
                .as("request must be denied")
                .isTrue();
    }

    private void unavailable(Reply reply) {
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().at("/errors/0/extensions/errorType").asString()).isEqualTo("GUEST_CART_UNAVAILABLE");
    }

    private void assertAmount(JsonNode amount, String expected) {
        assertThat(new BigDecimal(amount.asString())).isEqualByComparingTo(expected);
    }
}
