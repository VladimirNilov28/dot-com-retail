package ee.bytecore.backend.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.graphql.datafetchers.order.OrderMutation;
import ee.bytecore.backend.graphql.datafetchers.product.ProductMutation;
import ee.bytecore.backend.graphql.datafetchers.product.ProductQuery;
import ee.bytecore.backend.graphql.dataloaders.ProductVariantsByProductIdDataLoader;
import ee.bytecore.backend.repositories.category.CategoryRepository;
import ee.bytecore.backend.repositories.product.ProductRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.OrderService;
import ee.bytecore.backend.services.OrderStatusPublisher;
import ee.bytecore.backend.services.ProductService;
import ee.bytecore.backend.services.ProductVariantService;

import com.netflix.graphql.dgs.test.EnableDgsTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = HiveGraphQlTransportTest.Configuration.class,
        properties = {"spring.docker.compose.enabled=false", "spring.graphql.websocket.path=/graphql"})
@EnableDgsTest
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HiveGraphQlTransportTest {

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @Import({
        GraphQlHttpTransportTest.Configuration.class, ProductMutation.class, ProductQuery.class,
        ProductService.class, ProductVariantService.class, GraphQlExceptionResolver.class,
        OrderMutation.class, OrderStatusPublisher.class, CurrentUserProvider.class,
        ProductVariantsByProductIdDataLoader.class
    })
    static class Configuration {}

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper mapper;

    @Autowired
    OrderStatusPublisher publisher;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    ProductRepository productRepository;

    @MockitoBean
    ProductVariantRepository variantRepository;

    @MockitoBean
    CategoryRepository categoryRepository;

    @MockitoBean
    OrderService orderService;

    private GenericContainer<?> router;
    private final AtomicReference<ProductVariant> saved = new AtomicReference<>();

    @BeforeAll
    void startRouter() throws Exception {
        Testcontainers.exposeHostPorts(port);
        String graph = Files.readString(Path.of("../infrastructure/hive/supergraph.graphql"));
        String routed = graph.replace(
                "http://host.docker.internal:8080/graphql", "http://host.testcontainers.internal:" + port + "/graphql");
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

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode(anyString()))
                .thenReturn(Jwt.withTokenValue("hive-fixture")
                        .header("alg", "RS256")
                        .subject("1")
                        .claim("role", "ADMIN")
                        .claim("scope", "product:read product:write order:read")
                        .build());
        Product product = Product.create("Hive fixture", "hive-fixture", null);
        product.setId(1L);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(variantRepository.save(any(ProductVariant.class))).thenAnswer(invocation -> {
            ProductVariant variant = invocation.getArgument(0);
            variant.setId(1L);
            saved.set(variant);
            return variant;
        });
        when(variantRepository.findById(1L)).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(variantRepository.findAllByProductIdIn(any())).thenAnswer(invocation -> List.of(saved.get()));
    }

    @Test
    void shouldRoundTripLiteralAndVariableJsonThroughHiveTest() throws Exception {
        JsonNode literal = execute(Map.of(
                "query",
                "mutation { createProductVariant(input:{productId:1,sku:\"HIVE\",price:19.99,"
                        + "attributes:{enabled:true,nested:{sizes:[\"M\",\"L\"]}}}) { id attributes } }"));
        assertThat(literal.has("errors")).as("Hive response: %s", literal).isFalse();
        assertThat(literal.at("/data/createProductVariant/attributes/enabled").asBoolean())
                .isTrue();
        assertThat(literal.at("/data/createProductVariant/attributes/nested/sizes/1")
                        .asString())
                .isEqualTo("L");
        JsonNode updated = execute(Map.of(
                "query",
                "mutation($a:JSON){updateProductVariant(variantId:1,input:{attributes:$a}){attributes}}",
                "variables",
                Map.of("a", Map.of("count", 2, "nested", Map.of("enabled", false)))));
        assertThat(updated.has("errors")).isFalse();
        JsonNode read = execute(Map.of("query", "{product(id:1){variants{attributes}}}"));
        assertThat(read.at("/data/product/variants/0/attributes/count").asInt()).isEqualTo(2);
        assertThat(read.toString()).doesNotContain("nodeType", "objectNode", "valueNode");
    }

    @Test
    void shouldBindBothPriceFormsWithoutAttributesThroughHiveTest() throws Exception {
        for (String price : new String[] {"899.99", "\"899.99\""}) {
            JsonNode response = execute(Map.of(
                    "query",
                    "mutation {createProductVariant(input:{productId:1,sku:\"PRICE\",price:" + price
                            + "}){id price attributes}}"));
            assertThat(response.has("errors")).as("Hive response: %s", response).isFalse();
            assertThat(new BigDecimal(
                            response.at("/data/createProductVariant/price").asString()))
                    .isEqualByComparingTo("899.99");
            assertThat(response.at("/data/createProductVariant/attributes").isObject())
                    .isTrue();
        }
    }

    @Test
    void shouldReturnSafeBadRequestThroughHiveTest() throws Exception {
        HttpResponse<String> response = request("{\"foo\":\"bar\"}");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"errors\"").doesNotContain("stackTrace", "Exception", "hive-fixture");
    }

    @Test
    void shouldDeliverSubscriptionAfterReconnectThroughHiveTest() throws Exception {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(1L);
        when(order.getStatus()).thenReturn(OrderStatus.PAID);
        when(orderService.findById(1L)).thenReturn(Optional.of(order));
        Sinks.Many<?> sink = (Sinks.Many<?>) ReflectionTestUtils.getField(publisher, "sink");
        for (int reconnect = 0; reconnect < 2; reconnect++) {
            HttpRequest subscription = HttpRequest.newBuilder(URI.create(endpoint()))
                    .header("Authorization", "Bearer hive-fixture")
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"query\":\"subscription {orderStatusChanged(orderId:1){id status}}\"}"))
                    .build();
            var pending = HttpClient.newHttpClient().sendAsync(subscription, HttpResponse.BodyHandlers.ofLines());
            awaitSubscribers(sink, 1);
            publisher.publish(order);
            HttpResponse<java.util.stream.Stream<String>> response = pending.get(5, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            try (var lines = response.body()) {
                String data = CompletableFuture.supplyAsync(() -> lines.filter(line -> line.startsWith("data:"))
                                .findFirst()
                                .orElseThrow())
                        .get(5, TimeUnit.SECONDS);
                assertThat(mapper.readTree(data.substring(5))
                                .at("/data/orderStatusChanged/status")
                                .asString())
                        .isEqualTo("PAID");
            }
            awaitSubscribers(sink, 0);
        }
    }

    private void awaitSubscribers(Sinks.Many<?> sink, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sink.currentSubscriberCount() != count && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat(sink.currentSubscriberCount())
                .as("Hive logs: %s", router.getLogs())
                .isEqualTo(count);
    }

    private JsonNode execute(Map<String, Object> body) throws Exception {
        HttpResponse<String> response = request(mapper.writeValueAsString(body));
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> request(String body) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(endpoint()))
                                .header("Authorization", "Bearer hive-fixture")
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    private String endpoint() {
        return "http://" + router.getHost() + ":" + router.getMappedPort(4000) + "/graphql";
    }
}
