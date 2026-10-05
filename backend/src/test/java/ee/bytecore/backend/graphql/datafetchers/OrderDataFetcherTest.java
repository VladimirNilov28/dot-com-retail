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

import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.cart.CartItem;
import ee.bytecore.backend.entities.inventory.Inventory;
import ee.bytecore.backend.entities.inventory.Warehouse;
import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.graphql.datafetchers.order.OrderMutation;
import ee.bytecore.backend.graphql.datafetchers.order.OrderQuery;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.payment.OrderRepository;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.assertj.core.api.Assertions;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@SpringBootTest(
        classes = {
            OrderQuery.class,
            OrderMutation.class,
            GraphQLConfig.class,
            ee.bytecore.backend.graphql.GraphQlExceptionResolver.class,
            LocalDateScalar.class,
            InstantScalar.class,
            ee.bytecore.backend.services.OrderService.class,
            ee.bytecore.backend.config.CheckoutSettings.class,
            ee.bytecore.backend.services.OrderStatusPublisher.class,
            ee.bytecore.backend.services.CartService.class,
            ee.bytecore.backend.services.InventoryService.class,
            ee.bytecore.backend.security.CurrentUserProvider.class
        })
@EnableDgsMockMvcTest
@AutoConfigureHttpGraphQlTester
@Tag("graphql")
class OrderDataFetcherTest {

    @Autowired
    ee.bytecore.backend.services.OrderService checkoutOrderService;

    @MockitoBean
    ee.bytecore.backend.repositories.payment.CheckoutRequestRepository checkoutRequests;

    @MockitoBean
    org.springframework.jdbc.core.JdbcTemplate checkoutJdbc;

    @MockitoBean
    jakarta.persistence.EntityManager checkoutEntityManager;

    @MockitoBean
    OrderRepository orderRepository;

    @MockitoBean
    ee.bytecore.backend.repositories.payment.OrderItemRepository orderItemRepository;

    @MockitoBean
    ee.bytecore.backend.repositories.cart.CartRepository cartRepository;

    @MockitoBean
    ee.bytecore.backend.repositories.cart.CartItemRepository cartItemRepository;

    @MockitoBean
    ee.bytecore.backend.repositories.product.ProductVariantRepository productVariantRepository;

    @MockitoBean
    ee.bytecore.backend.repositories.inventory.InventoryRepository inventoryRepository;

    @MockitoBean
    ee.bytecore.backend.repositories.inventory.WarehouseRepository warehouseRepository;

    @MockitoBean
    ee.bytecore.backend.repositories.user.UserRepository userRepository;

    @MockitoBean
    ee.bytecore.backend.integration.payment.PaymentEventPublisher paymentEventPublisher;

    private User user;
    private Order order;

    @Autowired
    private GraphQlTester graphQlTester;

    @Autowired
    private ExecutionGraphQlService executionGraphQlService;

    @BeforeEach
    void setUp() {
        user = User.create("test-user", "test@example.com", LocalDate.of(1995, 6, 15));
        user.setId(1L);
        order = Order.create(user, OrderStatus.PENDING, new BigDecimal("39.98"));
        order.setId(1L);
        order.setPublicId(UUID.randomUUID());
        when(userRepository.findActiveByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
    }

    @Test
    @WithMockUser(username = "1")
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
    @WithMockUser(username = "1")
    void shouldResolveOrderForOrderItemTest() {
        Long id = order.getId();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));

        Product product = Product.create("T-Shirt", "t-shirt", "A plain t-shirt");
        product.setId(1L);
        ProductVariant variant = ProductVariant.create(product, "TSHIRT-M-BLACK", new BigDecimal("19.99"));
        variant.setId(1L);
        Warehouse warehouse = Warehouse.create("Main Warehouse", null);
        warehouse.setId(1L);
        Inventory inventory = Inventory.create(variant, warehouse, 5);
        inventory.setId(1L);
        ee.bytecore.backend.entities.payment.OrderItem item = ee.bytecore.backend.entities.payment.OrderItem.create(
                order, variant, inventory, 2, new BigDecimal("19.99"));
        item.setId(1L);
        when(orderItemRepository.findAllByOrderId(id)).thenReturn(List.of(item));
        when(orderItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        @Language("GraphQl")
        var query =
                """
            query($id: ID!) {
              order(id: $id) {
                items {
                  order {
                    id
                  }
                }
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("id", id)
                .execute()
                .path("order.items[0].order.id")
                .entity(String.class)
                .isEqualTo(order.getId().toString());
    }

    @Test
    @WithMockUser(username = "1")
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
    @WithMockUser(username = "1")
    void shouldRejectOrderQueryForAnotherUsersOrderTest() {
        User otherUser = User.create("other-user", "other@example.com", LocalDate.of(1990, 1, 1));
        otherUser.setId(2L);
        Order othersOrder = Order.create(otherUser, OrderStatus.PENDING, new BigDecimal("10.00"));
        othersOrder.setId(2L);
        when(orderRepository.findById(2L)).thenReturn(Optional.of(othersOrder));

        @Language("GraphQl")
        var query =
                """
            query($id: ID!) {
              order(id: $id) {
                status
              }
            }
        """;

        graphQlTester.document(query).variable("id", 2L).execute().errors().satisfy(errors -> Assertions.assertThat(
                        errors)
                .isNotEmpty());
    }

    @Test
    @WithMockUser(username = "1")
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
    @WithMockUser(username = "1")
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
    @WithMockUser(username = "1")
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
    @WithMockUser(username = "1")
    void shouldCreateOrderTest() {
        Cart cart = Cart.create(user);
        cart.setId(1L);
        Product product = Product.create("T-Shirt", "t-shirt", "A plain t-shirt");
        product.setId(1L);
        ProductVariant variant = ProductVariant.create(product, "TSHIRT-M-BLACK", new BigDecimal("19.99"));
        variant.setId(1L);
        cart.getItems().add(CartItem.create(cart, variant, 2));
        when(cartRepository.findByUserIdForUpdate(user.getId())).thenReturn(Optional.of(cart));
        Warehouse warehouse = Warehouse.create("Main Warehouse", null);
        warehouse.setId(1L);
        Inventory inventory = Inventory.create(variant, warehouse, 5);
        inventory.setId(1L);
        when(inventoryRepository.lockAllByProductVariantIdOrderByIdAsc(variant.getId()))
                .thenReturn(List.of(inventory));
        when(inventoryRepository.save(org.mockito.ArgumentMatchers.any(Inventory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(org.mockito.ArgumentMatchers.any(Order.class)))
                .thenReturn(order);
        when(cartRepository.findByUserId(user.getId())).thenReturn(Optional.of(cart));
        when(inventoryRepository.findAllByProductVariantId(variant.getId())).thenReturn(List.of(inventory));
        String quote = checkoutOrderService
                .previewCheckout(
                        user.getId(),
                        null,
                        new ee.bytecore.backend.services.CheckoutValues.Selection(
                                null, null, "PICKUP", null, null, null, null, false, "SIMULATED", null))
                .quoteVersion();

        @Language("GraphQl")
        var mutation =
                """
            mutation($quote: String!) {
              createOrder(input: {
                requestId: "00000000-0000-0000-0000-000000000001"
                acceptedQuoteVersion: $quote
                checkout: {shippingMethod: PICKUP}
              }) {
                status
                totalAmount
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("quote", quote)
                .execute()
                .path("createOrder.status")
                .entity(String.class)
                .isEqualTo(order.getStatus().name());
    }

    @Test
    @WithMockUser(username = "1")
    void shouldRejectCreateOrderWithEmptyCartTest() {
        Cart cart = Cart.create(user);
        cart.setId(1L);
        when(cartRepository.findByUserIdForUpdate(user.getId())).thenReturn(Optional.of(cart));
        when(orderRepository.save(org.mockito.ArgumentMatchers.any(Order.class)))
                .thenReturn(order);

        @Language("GraphQl")
        var mutation =
                """
            mutation {
              createOrder(input: {
                requestId: "00000000-0000-0000-0000-000000000001"
                acceptedQuoteVersion: "0000000000000000000000000000000000000000000000000000000000000000"
                checkout: {shippingMethod: PICKUP}
              }) {
                status
              }
            }
        """;

        graphQlTester.document(mutation).execute().errors().satisfy(errors -> Assertions.assertThat(errors)
                .as("creating an order from an empty cart must be rejected")
                .isNotEmpty());
    }

    @Test
    @WithMockUser(username = "1")
    void shouldUpdateOrderStatusTest() {
        Long id = order.getId();
        when(orderRepository.findByIdForUpdate(id)).thenReturn(Optional.of(order));
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
    @WithMockUser(username = "1")
    void shouldSubscribeToOrderStatusChangesTest() {
        Long id = order.getId();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        order.setStatus(OrderStatus.PAID);
        when(orderRepository.findByIdForUpdate(id)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

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

        // The publisher is multicast/non-replaying, so the verifier must subscribe
        // before the transition below runs, or it will miss the emitted value.
        var verifier = StepVerifier.create(flux)
                .expectNext(OrderStatus.SHIPPING.name())
                .thenCancel()
                .verifyLater();

        @Language("GraphQl")
        var mutation =
                """
            mutation($id: ID!) {
              updateOrderStatus(orderId: $id, input: { status: SHIPPING }) {
                status
              }
            }
        """;

        graphQlTester.document(mutation).variable("id", id).execute();

        verifier.verify(Duration.ofSeconds(5));
    }

    @Test
    @WithMockUser(username = "1")
    void shouldNotExposeCreatePaymentMutationTest() {
        @Language("GraphQl")
        var mutation =
                """
            mutation {
              createPayment(input: { orderId: "1", provider: "mastercard", type: CARD }) {
                status
              }
            }
        """;

        graphQlTester.document(mutation).execute().errors().satisfy(errors -> Assertions.assertThat(errors)
                .isNotEmpty());
    }

    @Test
    @WithMockUser(username = "1")
    void shouldNotExposeUpdatePaymentStatusMutationTest() {
        @Language("GraphQl")
        var mutation =
                """
            mutation {
              updatePaymentStatus(paymentId: "1", input: { status: SUCCESS }) {
                status
              }
            }
        """;

        graphQlTester.document(mutation).execute().errors().satisfy(errors -> Assertions.assertThat(errors)
                .isNotEmpty());
    }
}
