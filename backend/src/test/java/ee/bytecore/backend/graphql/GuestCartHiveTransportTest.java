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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.integration.payment.PaymentOutboxPublisher;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.services.CategoryService;
import ee.bytecore.backend.services.ProductService;
import ee.bytecore.backend.services.ProductVariantService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class GuestCartHiveTransportTest {

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    CategoryService categories;

    @Autowired
    ProductService products;

    @Autowired
    ProductVariantService variants;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PaymentOutboxPublisher outboxPublisher;

    private GenericContainer<?> router;
    private final HttpClient client = HttpClient.newHttpClient();

    private record CatalogFixture(
            String name,
            String productId,
            String categoryId,
            String variantId,
            String categorySlug,
            String parentSlug,
            String variantSku) {}

    private record CatalogOperation(
            String query, Map<String, Object> variables, String resultPath, String listField, String expectedId) {}

    @BeforeAll
    void startRouter() throws Exception {
        Testcontainers.exposeHostPorts(port);
        String graph = Files.readString(Path.of("../infrastructure/hive/supergraph.graphql"));
        String routed = graph.replace(
                "http://backend:8080/graphql", "http://host.testcontainers.internal:" + port + "/graphql");
        assertThat(routed).isNotEqualTo(graph);
        router = new GenericContainer<>("ghcr.io/graphql-hive/router:0.2.19")
                .withExposedPorts(4000)
                .withCopyToContainer(Transferable.of(routed), "/app/supergraph.graphql")
                .withCopyToContainer(
                        Transferable.of(Files.readString(Path.of("../infrastructure/hive/router.config.yaml"))),
                        "/app/router.config.yaml")
                .waitingFor(Wait.forHttp("/readiness").forPort(4000));
        router.start();
    }

    @AfterAll
    void stopRouter() {
        if (router != null) router.close();
    }

    @Test
    void shouldExposeAnonymousRatingAggregatesAndProtectOwnedWritesThroughHiveTest() throws Exception {
        CatalogFixture fixture = createCatalogFixture();
        String suffix = UUID.randomUUID().toString();
        User alice = users.save(
                User.create("rating-alice-" + suffix, "alice-" + suffix + "@example.com", LocalDate.of(2000, 1, 1)));
        User bob = users.save(
                User.create("rating-bob-" + suffix, "bob-" + suffix + "@example.com", LocalDate.of(2000, 1, 1)));
        when(jwtDecoder.decode("rating-alice"))
                .thenReturn(ratingJwt("rating-alice", alice.getId().toString(), "rating:write"));
        when(jwtDecoder.decode("rating-bob"))
                .thenReturn(ratingJwt("rating-bob", bob.getId().toString(), "rating:write"));
        when(jwtDecoder.decode("rating-no-scope"))
                .thenReturn(ratingJwt("rating-no-scope", alice.getId().toString(), ""));
        when(jwtDecoder.decode("rating-machine"))
                .thenReturn(ratingJwt("rating-machine", "machine-subject", "rating:write"));
        String mutation =
                """
                mutation Rate($id: ID!, $stars: Int!) {
                  rateProduct(productId: $id, stars: $stars) { id averageRating ratingCount }
                }
                """;
        Map<String, Object> variables = Map.of("id", fixture.productId(), "stars", 5);
        assertThat(postCatalog(mutation, null, variables, null, null).body()).contains("Authentication is required");
        for (String token : List.of("rating-no-scope", "rating-machine")) {
            assertThat(mapper.readTree(postCatalog(mutation, null, variables, null, token)
                                    .body())
                            .has("errors"))
                    .isTrue();
        }
        String query =
                """
                query Ratings($id: ID!, $term: String!) {
                  product(id: $id) { averageRating ratingCount }
                  products { id averageRating ratingCount }
                  searchProducts(input: {query: $term, sort: RATING_DESC, size: 2}) {
                    items { id averageRating ratingCount }
                    pageInfo { totalItems size }
                  }
                }
                """;
        Map<String, Object> read = Map.of("id", fixture.productId(), "term", fixture.name());
        JsonNode empty = success(postCatalog(query, null, read, null, null));
        assertThat(empty.at("/data/product/averageRating").isNull()).isTrue();
        assertThat(empty.at("/data/product/ratingCount").asInt()).isZero();
        assertThat(success(postCatalog(mutation, null, variables, null, "rating-alice"))
                        .at("/data/rateProduct/ratingCount")
                        .asInt())
                .isEqualTo(1);
        success(postCatalog(mutation, null, Map.of("id", fixture.productId(), "stars", 3), null, "rating-bob"));
        success(postCatalog(mutation, null, Map.of("id", fixture.productId(), "stars", 2), null, "rating-alice"));
        HttpResponse<String> response = postCatalog(query, null, read, null, null);
        JsonNode body = success(response);
        assertPublicCache(response);
        assertThat(body.at("/data/product/averageRating").asDouble()).isEqualTo(2.5);
        assertThat(body.at("/data/product/ratingCount").asInt()).isEqualTo(2);
        assertThat(body.at("/data/searchProducts/items/0/averageRating").asDouble())
                .isEqualTo(2.5);
        assertThat(body.at("/data/searchProducts/items/0/ratingCount").asInt()).isEqualTo(2);
        JsonNode listed = null;
        for (JsonNode item : body.at("/data/products")) {
            if (fixture.productId().equals(item.path("id").asText())) listed = item;
        }
        assertThat(listed).isNotNull();
        assertThat(listed.path("averageRating").asDouble()).isEqualTo(2.5);
        assertThat(listed.path("ratingCount").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT stars FROM product_ratings WHERE product_id=? AND user_id=?",
                        Integer.class,
                        Long.parseLong(fixture.productId()),
                        bob.getId()))
                .isEqualTo(3);
        for (int invalid : List.of(0, 6)) {
            assertThat(mapper.readTree(postCatalog(
                                            mutation,
                                            null,
                                            Map.of("id", fixture.productId(), "stars", invalid),
                                            null,
                                            "rating-alice")
                                    .body())
                            .has("errors"))
                    .isTrue();
        }
        for (String invalid : List.of("not-an-id", Long.toString(Long.MAX_VALUE))) {
            assertThat(mapper.readTree(
                                    postCatalog(mutation, null, Map.of("id", invalid, "stars", 5), null, "rating-alice")
                                            .body())
                            .has("errors"))
                    .isTrue();
        }
        assertThat(postCatalog(
                                "mutation { rateProduct(productId: \"" + fixture.productId()
                                        + "\", stars: 5, userId: \"" + bob.getId() + "\") { id } }",
                                null,
                                Map.of(),
                                null,
                                "rating-alice")
                        .body())
                .contains("errors");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM product_ratings WHERE product_id=?",
                        Integer.class,
                        Long.parseLong(fixture.productId())))
                .isEqualTo(2);
    }

    @Test
    void shouldServePublicReviewsAndEnforceOwnershipVotesAndModerationThroughHiveTest() throws Exception {
        CatalogFixture fixture = createCatalogFixture();
        String suffix = UUID.randomUUID().toString();
        User alice = users.save(
                User.create("review-alice-" + suffix, "ra-" + suffix + "@example.com", LocalDate.of(2000, 1, 1)));
        User bob = users.save(
                User.create("review-bob-" + suffix, "rb-" + suffix + "@example.com", LocalDate.of(2000, 1, 1)));
        User staff = users.save(
                User.create("review-staff-" + suffix, "rs-" + suffix + "@example.com", LocalDate.of(2000, 1, 1)));
        String a = alice.getId().toString();
        String b = bob.getId().toString();
        String m = staff.getId().toString();
        when(jwtDecoder.decode("review-alice")).thenReturn(jwt("review-alice", a, "USER", null, "rating:write"));
        when(jwtDecoder.decode("review-bob")).thenReturn(jwt("review-bob", b, "USER", null, "rating:write"));
        when(jwtDecoder.decode("review-bob-noscope")).thenReturn(jwt("review-bob-noscope", b, "USER", null, ""));
        when(jwtDecoder.decode("review-user-moderate"))
                .thenReturn(jwt("review-user-moderate", b, "USER", null, "review:moderate"));
        when(jwtDecoder.decode("review-manager"))
                .thenReturn(jwt("review-manager", m, "CATALOG_MANAGER", "aal2", "review:moderate"));
        when(jwtDecoder.decode("review-manager-aal1"))
                .thenReturn(jwt("review-manager-aal1", m, "CATALOG_MANAGER", "aal1", "review:moderate"));
        when(jwtDecoder.decode("review-manager-noscope"))
                .thenReturn(jwt("review-manager-noscope", m, "CATALOG_MANAGER", "aal2", "product:write"));
        when(jwtDecoder.decode("review-support"))
                .thenReturn(jwt("review-support", m, "SUPPORT", "aal2", "review:moderate"));

        String upsert =
                """
                mutation Upsert($id: ID!, $stars: Int!, $title: String!, $body: String!) {
                  upsertMyReview(productId: $id, stars: $stars, title: $title, body: $body) {
                    id title body stars status authorName viewerIsAuthor
                  }
                }
                """;
        Map<String, Object> review = Map.of(
                "id", fixture.productId(), "stars", 5, "title", "  Great  ", "body", "Works well for daily use.");
        assertThat(postCatalog(upsert, null, review, null, null).body()).contains("Authentication is required");
        assertThat(mapper.readTree(postCatalog(upsert, null, review, null, "review-bob-noscope")
                                .body())
                        .has("errors"))
                .isTrue();
        JsonNode created =
                success(postCatalog(upsert, null, review, null, "review-alice")).at("/data/upsertMyReview");
        String reviewId = created.path("id").asText();
        assertThat(created.path("title").asText()).isEqualTo("Great");
        assertThat(created.path("viewerIsAuthor").asBoolean()).isTrue();
        assertThat(created.path("authorName").asText()).isEqualTo("review-alice-" + suffix);
        Map<String, Object> html = new HashMap<>(review);
        html.put("body", "<img src=x onerror=alert(1)> long enough");
        assertThat(postCatalog(upsert, null, html, null, "review-alice").body()).contains("plain text without HTML");

        String list =
                """
                query Reviews($id: ID!) {
                  productReviews(productId: $id) {
                    items { id title helpfulCount viewerHasVoted viewerIsAuthor }
                    pageInfo { totalItems }
                    averageRating ratingCount distribution { stars count }
                  }
                }
                """;
        Map<String, Object> listVars = Map.of("id", fixture.productId());
        HttpResponse<String> anonymous = postCatalog(list, null, listVars, null, null);
        JsonNode page = success(anonymous).at("/data/productReviews");
        assertPublicCache(anonymous);
        assertThat(page.at("/items/0/id").asText()).isEqualTo(reviewId);
        assertThat(page.at("/items/0/viewerIsAuthor").asBoolean()).isFalse();
        assertThat(page.at("/ratingCount").asInt()).isEqualTo(1);
        assertThat(page.at("/distribution/0/stars").asInt()).isEqualTo(5);
        assertThat(page.at("/distribution/0/count").asInt()).isEqualTo(1);

        String vote = "mutation V($id: ID!) { voteReviewHelpful(reviewId: $id) { helpfulCount viewerHasVoted } }";
        Map<String, Object> voteVars = Map.of("id", reviewId);
        assertThat(postCatalog(vote, null, voteVars, null, "review-alice").body())
                .contains("errors");
        for (int i = 0; i < 2; i++) {
            JsonNode voted = success(postCatalog(vote, null, voteVars, null, "review-bob"))
                    .at("/data/voteReviewHelpful");
            assertThat(voted.path("helpfulCount").asInt()).isEqualTo(1);
            assertThat(voted.path("viewerHasVoted").asBoolean()).isTrue();
        }
        HttpResponse<String> bobView = postCatalog(list, null, listVars, null, "review-bob");
        assertPrivateNoStore(bobView);
        assertThat(success(bobView)
                        .at("/data/productReviews/items/0/viewerHasVoted")
                        .asBoolean())
                .isTrue();

        String delete = "mutation D($id: ID!) { deleteMyReview(productId: $id) }";
        assertThat(success(postCatalog(delete, null, listVars, null, "review-bob"))
                        .at("/data/deleteMyReview")
                        .asBoolean())
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM product_ratings WHERE id = ?", Long.class, Long.parseLong(reviewId)))
                .isEqualTo(1);

        String hide =
                "mutation H($id: ID!) { hideReview(reviewId: $id, reason: \"Spam link\") { status moderationReason } }";
        String moderation = "query M { moderationReviews(status: HIDDEN) { items { id } pageInfo { totalItems } } }";
        for (String token : List.of(
                "review-alice",
                "review-user-moderate",
                "review-manager-aal1",
                "review-manager-noscope",
                "review-support")) {
            assertThat(mapper.readTree(postCatalog(hide, null, voteVars, null, token)
                                    .body())
                            .has("errors"))
                    .as(token)
                    .isTrue();
            assertThat(mapper.readTree(postCatalog(moderation, null, Map.of(), null, token)
                                    .body())
                            .has("errors"))
                    .as(token)
                    .isTrue();
        }
        assertThat(postCatalog(hide, null, voteVars, null, null).body()).contains("Authentication is required");
        JsonNode hidden = success(postCatalog(hide, null, voteVars, null, "review-manager"))
                .at("/data/hideReview");
        assertThat(hidden.path("status").asText()).isEqualTo("HIDDEN");
        assertThat(hidden.path("moderationReason").asText()).isEqualTo("Spam link");
        assertThat(containsFieldValue(
                        success(postCatalog(moderation, null, Map.of(), null, "review-manager"))
                                .at("/data/moderationReviews/items"),
                        "id",
                        reviewId))
                .isTrue();
        JsonNode afterHide =
                success(postCatalog(list, null, listVars, null, null)).at("/data/productReviews");
        assertThat(afterHide.at("/items").size()).isZero();
        assertThat(afterHide.at("/ratingCount").asInt()).isZero();
        assertThat(afterHide.at("/averageRating").isNull()).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT actor_user_id FROM admin_audit_log WHERE operation = 'REVIEW_HIDE' AND target_id = ?",
                        Long.class,
                        reviewId))
                .isEqualTo(staff.getId());

        String restore = "mutation R($id: ID!) { restoreReview(reviewId: $id) { status } }";
        assertThat(success(postCatalog(restore, null, voteVars, null, "review-manager"))
                        .at("/data/restoreReview/status")
                        .asText())
                .isEqualTo("VISIBLE");
        assertThat(success(postCatalog(delete, null, listVars, null, "review-alice"))
                        .at("/data/deleteMyReview")
                        .asBoolean())
                .isTrue();
    }

    private Jwt jwt(String token, String subject, String role, String aal, String scope) {
        Jwt.Builder builder = Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(subject)
                .claim("role", role)
                .claim("scope", scope);
        if (aal != null) builder.claim("aal", aal);
        return builder.build();
    }

    private Jwt ratingJwt(String token, String subject, String scope) {
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(subject)
                .claim("role", "USER")
                .claim("scope", scope)
                .build();
    }

    @Test
    void shouldAllowCredentialFreeReadsForEachPublicCatalogRootTest() throws Exception {
        CatalogFixture fixture = createCatalogFixture();
        List<CatalogOperation> operations = List.of(
                new CatalogOperation(
                        """
                        query PublicProduct($id: ID!) {
                          product(id: $id) {
                            id name
                            categories { slug parent { slug } }
                            variants { sku product { id } }
                          }
                        }
                        """,
                        Map.of("id", fixture.productId()),
                        "/data/product/id",
                        null,
                        fixture.productId()),
                new CatalogOperation(
                        "query PublicProducts { products { id name } }",
                        Map.of(),
                        "/data/products",
                        "id",
                        fixture.productId()),
                new CatalogOperation(
                        """
                        query PublicCategory($id: ID!) {
                          category(id: $id) { id parent { slug } }
                        }
                        """,
                        Map.of("id", fixture.categoryId()),
                        "/data/category/id",
                        null,
                        fixture.categoryId()),
                new CatalogOperation(
                        "query PublicCategories { categories { id slug } }",
                        Map.of(),
                        "/data/categories",
                        "id",
                        fixture.categoryId()),
                new CatalogOperation(
                        """
                        query PublicSearch($term: String!) {
                          searchProducts(input: { query: $term, size: 5 }) {
                            items { id name }
                            pageInfo { size }
                          }
                        }
                        """,
                        Map.of("term", fixture.name()),
                        "/data/searchProducts/items",
                        "id",
                        fixture.productId()),
                new CatalogOperation(
                        """
                        query PublicSuggestions($term: String!) {
                          productSearchSuggestions(query: $term, limit: 5) {
                            productId name slug
                          }
                        }
                        """,
                        Map.of("term", fixture.name()),
                        "/data/productSearchSuggestions",
                        "productId",
                        fixture.productId()),
                new CatalogOperation(
                        """
                        query PublicReviews($id: ID!) {
                          productReviews(productId: $id, size: 5, sort: HELPFUL) {
                            items { id }
                            pageInfo { totalItems }
                            ratingCount
                            distribution { stars count }
                          }
                        }
                        """,
                        Map.of("id", fixture.productId()),
                        "/data/productReviews/distribution",
                        "stars",
                        "5"));

        for (CatalogOperation operation : operations) {
            HttpResponse<String> response = postCatalog(operation.query(), null, operation.variables(), null, null);
            JsonNode body = success(response);
            assertThat(body.path("data").isObject()).isTrue();
            JsonNode result = body.at(operation.resultPath());
            if (operation.listField() == null) {
                assertThat(result.asText()).isEqualTo(operation.expectedId());
            } else {
                assertThat(containsFieldValue(result, operation.listField(), operation.expectedId()))
                        .isTrue();
            }
            if (operation.resultPath().equals("/data/product/id")) {
                assertThat(body.at("/data/product/categories/0/slug").asText()).isEqualTo(fixture.categorySlug());
                assertThat(body.at("/data/product/categories/0/parent/slug").asText())
                        .isEqualTo(fixture.parentSlug());
                assertThat(body.at("/data/product/variants/0/sku").asText()).isEqualTo(fixture.variantSku());
                assertThat(body.at("/data/product/variants/0/product/id").asText())
                        .isEqualTo(fixture.productId());
            }
            if (operation.resultPath().equals("/data/category/id")) {
                assertThat(body.at("/data/category/parent/slug").asText()).isEqualTo(fixture.parentSlug());
            }
            assertPublicCache(response);
        }
    }

    @Test
    void shouldClassifyOnlyTheSelectedOperationAndResolveAliasesAndFragmentsTest() throws Exception {
        String query =
                """
                query ProtectedOperation { me { id } }
                query PublicCatalog { ...CatalogFields }
                fragment CatalogFields on Query { selectedCategories: categories { id } }
                """;

        HttpResponse<String> publicResponse = postCatalog(query, "PublicCatalog", Map.of(), null, null);
        assertThat(success(publicResponse).at("/data/selectedCategories").isArray())
                .isTrue();
        assertPublicCache(publicResponse);

        JsonNode protectedResponse = mapper.readTree(
                postCatalog(query, "ProtectedOperation", Map.of(), null, null).body());
        assertThat(protectedResponse.path("errors").isArray()).isTrue();
        assertThat(protectedResponse.toString()).contains("Authentication is required");
    }

    @Test
    void shouldRejectPublicCatalogDocumentsMixedWithProtectedOrGuestRootsTest() throws Exception {
        for (String query : List.of(
                "query Mixed { catalog: categories { id } private: me { id } }",
                "query Mixed { catalog: categories { id } guestCart { id } }")) {
            JsonNode body = mapper.readTree(
                    postCatalog(query, null, Map.of(), null, null).body());
            assertThat(body.path("errors").isArray()).isTrue();
            assertThat(body.toString()).contains("cannot be combined");
        }
    }

    @Test
    void shouldKeepProtectedRootsAndCatalogWritesUnavailableWithoutCredentialsTest() throws Exception {
        for (String query : List.of(
                "{ me { id } }",
                "{ myCart { id } }",
                "{ myWishlist { id } }",
                "{ myOrders { id } }",
                "{ warehouses { id } }",
                """
                mutation {
                  createProduct(input: { name: "Should not exist", slug: "anonymous-write" }) { id }
                }
                """)) {
            JsonNode body = mapper.readTree(
                    postCatalog(query, null, Map.of(), null, null).body());
            assertThat(body.path("errors").isArray()).isTrue();
            assertThat(body.toString()).contains("Authentication is required");
        }
    }

    @Test
    void shouldKeepCatalogScopeChecksAndRejectInvalidBearerTokensTest() throws Exception {
        CatalogFixture fixture = createCatalogFixture();
        when(jwtDecoder.decode("catalog-reader"))
                .thenReturn(Jwt.withTokenValue("catalog-reader")
                        .header("alg", "RS256")
                        .subject("42")
                        .claim("role", "USER")
                        .claim("scope", "product:read category:read")
                        .build());
        when(jwtDecoder.decode("no-catalog-scopes"))
                .thenReturn(Jwt.withTokenValue("no-catalog-scopes")
                        .header("alg", "RS256")
                        .subject("42")
                        .claim("role", "USER")
                        .claim("scope", "cart:read")
                        .build());
        when(jwtDecoder.decode("invalid-public-token"))
                .thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("Invalid fixture token"));

        HttpResponse<String> scoped = postCatalog(
                "query Scoped($id: ID!) { product(id: $id) { id } }",
                null,
                Map.of("id", fixture.productId()),
                null,
                "catalog-reader");
        assertThat(success(scoped).at("/data/product/id").asString()).isEqualTo(fixture.productId());
        assertPrivateNoStore(scoped);

        HttpResponse<String> insufficientScope =
                postCatalog("query Scoped { categories { id } }", null, Map.of(), null, "no-catalog-scopes");
        assertThat(insufficientScope.statusCode())
                .as(
                        "Authenticated caller without catalog scope: HTTP %s body %s",
                        insufficientScope.statusCode(), insufficientScope.body())
                .isIn(200, 401, 403);
        if (insufficientScope.statusCode() == 200) {
            assertThat(mapper.readTree(insufficientScope.body()).path("errors").isArray())
                    .isTrue();
        }

        HttpResponse<String> invalidToken =
                postCatalog("query InvalidToken { categories { id } }", null, Map.of(), null, "invalid-public-token");
        assertThat(invalidToken.statusCode()).isIn(200, 401);
        if (invalidToken.statusCode() == 200) {
            JsonNode invalidBody = mapper.readTree(invalidToken.body());
            assertThat(invalidBody.path("errors").isArray())
                    .as("Hive response for invalid bearer: %s", invalidBody)
                    .isTrue();
        }
    }

    @Test
    void shouldDenyAnonymousInventoryTraversalAllowScopedAccessAndKeepCredentialedReadsPrivateTest() throws Exception {
        CatalogFixture fixture = createCatalogFixture();
        String warehouseName = "Public access warehouse " + UUID.randomUUID();
        Long warehouseId =
                jdbc.queryForObject("INSERT INTO warehouses(name) VALUES (?) RETURNING id", Long.class, warehouseName);
        jdbc.update(
                "INSERT INTO inventory(product_variant_id,warehouse_id,quantity) VALUES (?,?,?)",
                Long.valueOf(fixture.variantId()),
                warehouseId,
                17);

        when(jwtDecoder.decode("catalog-and-warehouse-reader"))
                .thenReturn(Jwt.withTokenValue("catalog-and-warehouse-reader")
                        .header("alg", "RS256")
                        .subject("42")
                        .claim("role", "USER")
                        .claim("scope", "product:read category:read warehouse:read")
                        .build());
        String traversalQuery =
                """
                query InventoryTraversal($id: ID!) {
                  product(id: $id) {
                    id name
                    variants { inventory { quantity warehouse { id name } } }
                  }
                  categories { id }
                }
                """;
        Map<String, Object> variables = Map.of("id", fixture.productId());

        HttpResponse<String> authorized =
                postCatalog(traversalQuery, null, variables, null, "catalog-and-warehouse-reader");
        JsonNode authorizedBody = success(authorized);
        assertThat(authorizedBody
                        .at("/data/product/variants/0/inventory/0/quantity")
                        .asInt())
                .isEqualTo(17);
        assertThat(authorizedBody
                        .at("/data/product/variants/0/inventory/0/warehouse/id")
                        .asText())
                .isEqualTo(warehouseId.toString());
        assertThat(authorizedBody
                        .at("/data/product/variants/0/inventory/0/warehouse/name")
                        .asText())
                .isEqualTo(warehouseName);
        assertPrivateNoStore(authorized);

        JsonNode inventoryResponse = mapper.readTree(
                postCatalog(traversalQuery, null, variables, null, null).body());
        JsonNode authorizationError = inventoryResponse.at("/errors/0");
        assertThat(authorizationError.path("message").asString()).containsIgnoringCase("access denied");
        assertThat(authorizationError.path("path").toString()).isEqualTo("[\"product\",\"variants\",0,\"inventory\"]");
        assertThat(inventoryResponse.at("/data/product").isNull()).isTrue();
        assertThat(containsFieldValue(inventoryResponse.at("/data/categories"), "id", fixture.categoryId()))
                .isTrue();

        HttpResponse<String> cookieBearing = postCatalog(
                "query PublicCategories { categories { id } }", null, Map.of(), "retail_guest_cart=opaque", null);
        assertThat(success(cookieBearing).path("data").isObject()).isTrue();
        assertPrivateNoStore(cookieBearing);
    }

    @Test
    void shouldKeepGuestCartOriginHeaderAndNoStoreRequirementsSeparateFromPublicReadsTest() throws Exception {
        JsonNode rejectedGuestRead = mapper.readTree(
                postCatalog("{ guestCart { id } }", null, Map.of(), null, null).body());
        assertThat(rejectedGuestRead.toString()).contains("allowed Origin and X-Guest-Cart-Request");
        JsonNode rejectedGuestOrderRead = mapper.readTree(postCatalog(
                        "query GuestOrder { guestOrder(requestId: \"" + UUID.randomUUID() + "\") { publicId } }",
                        null,
                        Map.of(),
                        null,
                        null)
                .body());
        assertThat(rejectedGuestOrderRead.toString()).contains("allowed Origin and X-Guest-Cart-Request");

        HttpResponse<String> guestRead = post("{ guestCart { id } }", Map.of(), null, false);
        assertThat(success(guestRead).at("/data/guestCart").isNull()).isTrue();
        assertPrivateNoStore(guestRead);
    }

    @Test
    void shouldPropagateGuestCookiePersistCartAndMergeWithJwtThroughHiveTest() throws Exception {
        String suffix = UUID.randomUUID().toString();
        User owner = users.save(User.create("hive-guest-" + suffix, suffix + "@example.com", LocalDate.of(1990, 1, 1)));
        when(jwtDecoder.decode(anyString()))
                .thenReturn(Jwt.withTokenValue("guest-hive-fixture")
                        .header("alg", "RS256")
                        .subject(owner.getId().toString())
                        .claim("role", "USER")
                        .claim("scope", "cart:read cart:write")
                        .build());
        var started = post("mutation{startGuestCart{cart{id} created}}", Map.of(), null, false);
        JsonNode start = success(started);
        HttpCookie credential = started.headers().allValues("Set-Cookie").stream()
                .flatMap(value -> HttpCookie.parse(value).stream())
                .filter(cookie -> "retail_guest_cart".equals(cookie.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Hive did not propagate the guest cookie"));
        assertThat(credential.isHttpOnly()).isTrue();
        assertThat(credential.getPath()).isEqualTo("/graphql");
        String cookie = credential.getName() + "=" + credential.getValue();
        String guestId = start.at("/data/startGuestCart/cart/id").asString();
        assertThat(success(post("{guestCart{id totals{subtotal}}}", Map.of(), cookie, false))
                        .at("/data/guestCart/id")
                        .asString())
                .isEqualTo(guestId);
        assertThat(success(post("{guestCart{id}}", Map.of(), null, false))
                        .at("/data/guestCart")
                        .isNull())
                .isTrue();
        assertThat(post("{myCart{id}}", Map.of(), cookie, false).body()).contains("errors");
        UUID requestId = UUID.randomUUID();
        String mutation = "mutation($id:UUID!){mergeGuestCart(requestId:$id){status cart{id} conflicts{code}}}";
        var merged = post(mutation, Map.of("id", requestId.toString()), cookie, true);
        assertThat(success(merged).at("/data/mergeGuestCart/status").asString()).isEqualTo("MERGED");
        assertThat(merged.headers().allValues("Set-Cookie").stream()
                        .flatMap(value -> HttpCookie.parse(value).stream())
                        .anyMatch(value -> "retail_guest_cart".equals(value.getName()) && value.getMaxAge() == 0))
                .isTrue();
        assertThat(success(post(mutation, Map.of("id", requestId.toString()), null, true))
                        .at("/data/mergeGuestCart/status")
                        .asString())
                .isEqualTo("REPLAYED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE user_id=?", Integer.class, owner.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts WHERE id=?", Integer.class, Long.parseLong(guestId)))
                .isZero();
        assertThat(post("{guestCart{id}}", Map.of(), cookie, false).body()).contains("GUEST_CART_UNAVAILABLE");
    }

    private HttpResponse<String> post(String query, Map<String, Object> variables, String cookie, boolean authenticated)
            throws Exception {
        var builder = HttpRequest.newBuilder(
                        URI.create("http://" + router.getHost() + ":" + router.getMappedPort(4000) + "/graphql"))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:3000")
                .header("X-Guest-Cart-Request", "1")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(Map.of("query", query, "variables", variables))));
        if (cookie != null) builder.header("Cookie", cookie);
        if (authenticated) builder.header("Authorization", "Bearer guest-hive-fixture");
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postCatalog(
            String query, String operationName, Map<String, Object> variables, String cookie, String authorization)
            throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("query", query);
        payload.put("variables", variables);
        if (operationName != null) payload.put("operationName", operationName);
        var builder = HttpRequest.newBuilder(
                        URI.create("http://" + router.getHost() + ":" + router.getMappedPort(4000) + "/graphql"))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
        if (cookie != null) builder.header("Cookie", cookie);
        if (authorization != null) builder.header("Authorization", "Bearer " + authorization);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private CatalogFixture createCatalogFixture() {
        String suffix = UUID.randomUUID().toString();
        var parent = categories.create("Public parent " + suffix, "public-parent-" + suffix, null);
        var category = categories.create("Public category " + suffix, "public-category-" + suffix, parent.getId());
        String name = "Public product " + suffix;
        var product = products.create(
                name, "public-product-" + suffix, "Public catalog test fixture", List.of(category.getId()));
        var variant = variants.create(
                product.getId(), "PUBLIC-" + suffix, new BigDecimal("12.34"), Map.of("color", "blue"), null, null);
        return new CatalogFixture(
                name,
                product.getId().toString(),
                category.getId().toString(),
                variant.getId().toString(),
                category.getSlug(),
                parent.getSlug(),
                "PUBLIC-" + suffix);
    }

    private void assertPublicCache(HttpResponse<String> response) {
        String cacheControl = response.headers().firstValue("Cache-Control").orElse("");
        assertThat(cacheControl).contains("public").contains("max-age=60").doesNotContain("no-store");
        String vary = String.join(", ", response.headers().allValues("Vary"));
        assertThat(vary)
                .containsIgnoringCase("Origin")
                .containsIgnoringCase("Authorization")
                .containsIgnoringCase("Cookie");
    }

    private void assertPrivateNoStore(HttpResponse<String> response) {
        String cacheControl = response.headers().firstValue("Cache-Control").orElse("");
        assertThat(cacheControl).containsIgnoringCase("no-store").doesNotContain("public");
    }

    private boolean containsFieldValue(JsonNode array, String field, String expectedValue) {
        for (JsonNode item : array) {
            if (expectedValue.equals(item.path(field).asString())) return true;
        }
        return false;
    }

    private JsonNode success(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.has("errors")).as("Hive GraphQL response: %s", body).isFalse();
        return body;
    }
}
