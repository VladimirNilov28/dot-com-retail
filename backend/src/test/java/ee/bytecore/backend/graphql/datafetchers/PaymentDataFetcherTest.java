package ee.bytecore.backend.graphql.datafetchers;

import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.payment.PaymentDetails;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.enums.PaymentMethodType;
import ee.bytecore.backend.enums.PaymentStatus;
import ee.bytecore.backend.graphql.datafetchers.payment.PaymentDataFetcher;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.payment.OrderRepository;
import ee.bytecore.backend.repositories.payment.PaymentDetailsRepository;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(classes = {PaymentDataFetcher.class, GraphQLConfig.class, LocalDateScalar.class, InstantScalar.class})
@EnableDgsMockMvcTest
@AutoConfigureHttpGraphQlTester
@Tag("graphql")
class PaymentDataFetcherTest {

    @MockitoBean
    OrderRepository orderRepository;

    @MockitoBean
    PaymentDetailsRepository paymentDetailsRepository;

    private User user;
    private Order order;
    private PaymentDetails paymentDetails;

    @Autowired
    private GraphQlTester graphQlTester;

    @Autowired
    private ExecutionGraphQlService executionGraphQlService;

    @BeforeEach
    void setUp() {
        user = User.create("test-user", "test@example.com", "hashed-password", LocalDate.of(1995, 6, 15));
        order = Order.create(user, OrderStatus.PENDING, new BigDecimal("39.98"));
        paymentDetails = PaymentDetails.create(order, new BigDecimal("39.98"), "mastercard", PaymentMethodType.CARD);
    }

    @Test
    @WithMockUser
    void shouldReturnOrderByIdTest() {
        Long id = order.getId();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));

        @Language("GraphQl")
        var query =
                """
            query($id: ID!) {
              order(id: $id) {
                status
                totalAmount
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("id", id)
                .execute()
                .path("order.status")
                .entity(String.class)
                .isEqualTo(order.getStatus().name())
                .path("order.totalAmount")
                .entity(BigDecimal.class)
                .isEqualTo(order.getTotalAmount());
    }

    @Test
    @WithMockUser
    void shouldReturnOrderByPublicIdTest() {
        UUID publicId = order.getPublicId();
        when(orderRepository.findByPublicId(publicId)).thenReturn(Optional.of(order));

        @Language("GraphQl")
        var query =
                """
            query($publicId: UUID!) {
              order(publicId: $publicId) {
                status
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("publicId", publicId)
                .execute()
                .path("order.status")
                .entity(String.class)
                .isEqualTo(order.getStatus().name());
    }

    @Test
    @WithMockUser
    void shouldReturnNullWhenOrderNotFoundByPublicIdTest() {
        UUID publicId = UUID.randomUUID();
        when(orderRepository.findByPublicId(publicId)).thenReturn(Optional.empty());

        @Language("GraphQl")
        var query =
                """
            query($publicId: UUID!) {
              order(publicId: $publicId) {
                status
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("publicId", publicId)
                .execute()
                .path("order")
                .valueIsNull();
    }

    @Test
    @WithMockUser(username = "test-user")
    void shouldReturnMyOrdersTest() {
        when(orderRepository.findAllByUserId(user.getId())).thenReturn(List.of(order));

        @Language("GraphQl")
        var query =
                """
            query {
              myOrders {
                status
              }
            }
        """;

        graphQlTester
                .document(query)
                .execute()
                .path("myOrders[0].status")
                .entity(String.class)
                .isEqualTo(order.getStatus().name());
    }

    @Test
    @WithMockUser(username = "test-user")
    void shouldReturnEmptyMyOrdersTest() {
        when(orderRepository.findAllByUserId(user.getId())).thenReturn(List.of());

        @Language("GraphQl")
        var query =
                """
            query {
              myOrders {
                status
              }
            }
        """;

        graphQlTester
                .document(query)
                .execute()
                .path("myOrders")
                .entityList(Object.class)
                .hasSize(0);
    }

    @Test
    @WithMockUser(username = "test-user")
    void shouldCreateOrderTest() {
        when(orderRepository.save(org.mockito.ArgumentMatchers.any(Order.class)))
                .thenReturn(order);

        @Language("GraphQl")
        var mutation =
                """
            mutation {
              createOrder {
                status
                totalAmount
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .execute()
                .path("createOrder.status")
                .entity(String.class)
                .isEqualTo(order.getStatus().name());
    }

    @Test
    @WithMockUser(username = "test-user")
    void shouldUpdateOrderStatusTest() {
        Long id = order.getId();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              updateOrderStatus(orderId: $id, input: { status: PAID }) {
                status
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("id", id)
                .execute()
                .path("updateOrderStatus.status")
                .entity(String.class)
                .isEqualTo(OrderStatus.PAID.name());
    }

    @Test
    @WithMockUser(username = "test-user")
    void shouldCreatePaymentTest() {
        Long orderId = order.getId();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(paymentDetailsRepository.save(org.mockito.ArgumentMatchers.any(PaymentDetails.class)))
                .thenReturn(paymentDetails);

        @Language("GraphQl")
        var mutation =
                """
            mutation($orderId: ID!) {
              createPayment(input: { orderId: $orderId, provider: "mastercard", type: CARD }) {
                provider
                type
                status
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("orderId", orderId)
                .execute()
                .path("createPayment.provider")
                .entity(String.class)
                .isEqualTo(paymentDetails.getProvider())
                .path("createPayment.type")
                .entity(String.class)
                .isEqualTo(paymentDetails.getType().name())
                .path("createPayment.status")
                .entity(String.class)
                .isEqualTo(PaymentStatus.PENDING.name());
    }

    @Test
    @WithMockUser(username = "test-user")
    void shouldUpdatePaymentStatusTest() {
        Long id = paymentDetails.getId();
        when(paymentDetailsRepository.findById(id)).thenReturn(Optional.of(paymentDetails));
        when(paymentDetailsRepository.save(paymentDetails)).thenReturn(paymentDetails);

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              updatePaymentStatus(paymentId: $id, input: { status: SUCCESS }) {
                status
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("id", id)
                .execute()
                .path("updatePaymentStatus.status")
                .entity(String.class)
                .isEqualTo(PaymentStatus.SUCCESS.name());
    }

    @Test
    @WithMockUser(username = "test-user")
    void shouldSubscribeToOrderStatusChangesTest() {
        Long id = order.getId();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));

        @Language("GraphQl")
        var subscription =
                """
            subscription($id: ID!) {
              orderStatusChanged(orderId: $id) {
                status
              }
            }
        """;

        // HttpGraphQlTester's MockMvc transport doesn't support subscriptions; execute directly against the engine.
        var subscriptionTester = ExecutionGraphQlServiceTester.create(executionGraphQlService);

        var flux = subscriptionTester
                .document(subscription)
                .variable("id", id)
                .executeSubscription()
                .toFlux("orderStatusChanged.status", String.class);

        var firstStatus = flux.blockFirst(Duration.ofSeconds(5));

        Assertions.assertEquals(OrderStatus.PAID.name(), firstStatus);
    }
}
