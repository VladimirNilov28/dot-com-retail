package ee.bytecore.backend.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import ee.bytecore.backend.config.SecurityConfig;
import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.graphql.datafetchers.order.OrderMutation;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.OrderService;
import ee.bytecore.backend.services.OrderStatusPublisher;

import com.netflix.graphql.dgs.test.EnableDgsTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Sinks;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = OrderSubscriptionTransportTest.Configuration.class,
        properties = {"spring.docker.compose.enabled=false", "spring.graphql.websocket.path=/graphql"})
@EnableDgsTest
@Tag("graphql")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OrderSubscriptionTransportTest {

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({
        SecurityConfig.class,
        GraphQLConfig.class,
        InstantScalar.class,
        LocalDateScalar.class,
        OrderMutation.class,
        OrderStatusPublisher.class,
        CurrentUserProvider.class
    })
    static class Configuration {}

    @LocalServerPort
    int port;

    @Autowired
    OrderStatusPublisher publisher;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    OrderService orderService;

    private Order order;

    @BeforeEach
    void setUp() {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(1L);
        order = mock(Order.class);
        when(order.getId()).thenReturn(1L);
        when(order.getUser()).thenReturn(owner);
        when(order.getStatus()).thenReturn(OrderStatus.PAID);
        when(orderService.findById(1L)).thenReturn(Optional.of(order));
        when(orderService.requireReadable(any(Order.class), any(), any(Boolean.class)))
                .thenCallRealMethod();
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            if (!java.util.Set.of("owner", "foreign", "admin", "staff", "missing")
                    .contains(token)) {
                throw new BadJwtException("Invalid test token");
            }
            String role =
                    switch (token) {
                        case "admin" -> "ADMIN";
                        case "staff" -> "ORDER_MANAGER";
                        default -> "USER";
                    };
            return Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject(token.equals("owner") ? "1" : "2")
                    .claim("role", role)
                    .claim("scope", token.equals("missing") ? "" : "order:read")
                    .build();
        });
    }

    @Test
    void shouldDeliverRepeatedUpdatesAfterReconnectTest() throws Exception {
        try (Connection first = connect("owner")) {
            first.subscribe();
            awaitSubscribers(1);
            Order other = mock(Order.class);
            when(other.getId()).thenReturn(2L);
            publisher.publish(other);
            assertThat(first.messages.poll(100, TimeUnit.MILLISECONDS)).isNull();
            publisher.publish(order);
            assertThat(first.next()).contains("\"type\":\"next\"", "\"status\":\"PAID\"");
        }
        awaitSubscribers(0);
        try (Connection second = connect("owner")) {
            second.subscribe();
            awaitSubscribers(1);
            when(order.getStatus()).thenReturn(OrderStatus.SHIPPING);
            publisher.publish(order);
            assertThat(second.next()).contains("\"type\":\"next\"", "\"status\":\"SHIPPING\"");
            when(order.getStatus()).thenReturn(OrderStatus.COMPLETED);
            publisher.publish(order);
            assertThat(second.next()).contains("\"type\":\"next\"", "\"status\":\"COMPLETED\"");
        }
    }

    @Test
    void shouldRejectForeignOwnerTest() throws Exception {
        try (Connection connection = connect("foreign")) {
            connection.subscribe();
            String response = null;
            for (int attempt = 0; attempt < 100 && response == null; attempt++) {
                publisher.publish(order);
                response = connection.messages.poll(50, TimeUnit.MILLISECONDS);
            }
            assertThat(response).isNotNull().containsAnyOf("FORBIDDEN", "PERMISSION_DENIED");
            assertThat(response).doesNotContain("\"status\":\"PAID\"", "stackTrace");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "staff"})
    void shouldAllowScopedOrderStaffTest(String token) throws Exception {
        try (Connection connection = connect(token)) {
            connection.subscribe();
            awaitSubscribers(1);
            publisher.publish(order);
            assertThat(connection.next()).contains("\"type\":\"next\"", "\"status\":\"PAID\"");
        }
    }

    @Test
    void shouldRejectMissingScopeTest() throws Exception {
        try (Connection connection = connect("missing")) {
            connection.subscribe();
            assertThat(connection.next()).containsAnyOf("FORBIDDEN", "PERMISSION_DENIED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid"})
    void shouldRejectUnauthenticatedHandshakeTest(String token) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> connect(token));
        assertThat(failure.getCause()).isInstanceOf(WebSocketHandshakeException.class);
        assertThat(((WebSocketHandshakeException) failure.getCause())
                        .getResponse()
                        .statusCode())
                .isEqualTo(401);
    }

    @Test
    void shouldAuthenticateConnectionInitUsingExistingJwtDecoderTest() throws Exception {
        try (Connection connection = connectWithoutHeader()) {
            connection
                    .socket
                    .sendText("{\"type\":\"connection_init\",\"payload\":{\"authorization\":\"Bearer owner\"}}", true)
                    .get(5, TimeUnit.SECONDS);
            assertThat(connection.next()).contains("\"type\":\"connection_ack\"");
            connection.subscribe();
            awaitSubscribers(1);
            publisher.publish(order);
            assertThat(connection.next()).contains("\"status\":\"PAID\"");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "staff"})
    void shouldAllowScopedStaffAuthenticatedThroughConnectionInitTest(String token) throws Exception {
        try (Connection connection = connectWithoutHeader()) {
            initialize(connection, token);
            connection.subscribe();
            awaitSubscribers(1);
            publisher.publish(order);
            assertThat(connection.next()).contains("\"status\":\"PAID\"");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "missing"})
    void shouldEnforceOwnershipAndScopeForConnectionInitAuthenticationTest(String token) throws Exception {
        try (Connection connection = connectWithoutHeader()) {
            initialize(connection, token);
            connection.subscribe();
            String response = connection.next();
            assertThat(response).containsAnyOf("FORBIDDEN", "PERMISSION_DENIED");
            assertThat(response).doesNotContain("\"status\":\"PAID\"", "stackTrace");
            awaitSubscribers(0);
        }
    }

    @Test
    void shouldRejectOperationsBeforeConnectionInitTest() throws Exception {
        try (Connection connection = connectWithoutHeader()) {
            connection.subscribe();
            assertThat(connection.closed.get(5, TimeUnit.SECONDS)).isEqualTo(4401);
            assertThat(connection.messages).isEmpty();
            awaitSubscribers(0);
        }
    }

    @Test
    void shouldRejectInvalidPayloadEvenWithAuthenticatedUpgradeTest() throws Exception {
        Connection connection = new Connection();
        connection.socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .subprotocols("graphql-transport-ws")
                .header("Authorization", "Bearer owner")
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/graphql"), connection)
                .get(5, TimeUnit.SECONDS);
        try (connection) {
            connection
                    .socket
                    .sendText("{\"type\":\"connection_init\",\"payload\":{\"Authorization\":\"Bearer invalid\"}}", true)
                    .get(5, TimeUnit.SECONDS);
            assertThat(connection.closed.get(5, TimeUnit.SECONDS)).isNotEqualTo(WebSocket.NORMAL_CLOSURE);
            assertThat(connection.messages).isEmpty();
            awaitSubscribers(0);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"Authorization\":\"Bearer invalid\"}",
                "{\"Authorization\":123}",
                "{\"Authorization\":\"Basic invalid\"}"
            })
    void shouldRejectMissingInvalidAndMalformedConnectionInitAuthenticationTest(String payload) throws Exception {
        try (Connection connection = connectWithoutHeader()) {
            connection
                    .socket
                    .sendText("{\"type\":\"connection_init\",\"payload\":" + payload + "}", true)
                    .get(5, TimeUnit.SECONDS);
            assertThat(connection.closed.get(5, TimeUnit.SECONDS)).isNotEqualTo(WebSocket.NORMAL_CLOSURE);
            assertThat(connection.messages).isEmpty();
            awaitSubscribers(0);
        }
    }

    private Connection connectWithoutHeader() throws Exception {
        Connection connection = new Connection();
        connection.socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .subprotocols("graphql-transport-ws")
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/graphql"), connection)
                .get(5, TimeUnit.SECONDS);
        return connection;
    }

    private void initialize(Connection connection, String token) throws Exception {
        connection
                .socket
                .sendText(
                        "{\"type\":\"connection_init\",\"payload\":{\"Authorization\":\"Bearer " + token + "\"}}", true)
                .get(5, TimeUnit.SECONDS);
        assertThat(connection.next()).contains("\"type\":\"connection_ack\"");
    }

    private Connection connect(String token) throws Exception {
        Connection connection = new Connection();
        WebSocket.Builder builder =
                HttpClient.newHttpClient().newWebSocketBuilder().subprotocols("graphql-transport-ws");
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        connection.socket = builder.buildAsync(URI.create("ws://127.0.0.1:" + port + "/graphql"), connection)
                .get(5, TimeUnit.SECONDS);
        connection.socket.sendText("{\"type\":\"connection_init\"}", true).get(5, TimeUnit.SECONDS);
        assertThat(connection.next()).contains("\"type\":\"connection_ack\"");
        return connection;
    }

    private void awaitSubscribers(int count) throws Exception {
        Sinks.Many<?> sink = (Sinks.Many<?>) ReflectionTestUtils.getField(publisher, "sink");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sink.currentSubscriberCount() != count && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat(sink.currentSubscriberCount()).isEqualTo(count);
    }

    private static class Connection implements WebSocket.Listener, AutoCloseable {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder frame = new StringBuilder();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private WebSocket socket;

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            frame.append(text);
            if (last) {
                messages.add(frame.toString());
                frame.setLength(0);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            closed.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }

        void subscribe() throws Exception {
            socket.sendText(
                            "{\"id\":\"stream\",\"type\":\"subscribe\",\"payload\":{\"query\":"
                                    + "\"subscription { orderStatusChanged(orderId: 1) { id status } }\"}}",
                            true)
                    .get(5, TimeUnit.SECONDS);
        }

        String next() throws Exception {
            String next = messages.poll(5, TimeUnit.SECONDS);
            assertThat(next).as("GraphQL WebSocket response").isNotNull();
            return next;
        }

        @Override
        public void close() throws Exception {
            if (closed.isDone()) {
                socket.abort();
                return;
            }
            if (!socket.isOutputClosed()) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
            }
        }
    }
}
