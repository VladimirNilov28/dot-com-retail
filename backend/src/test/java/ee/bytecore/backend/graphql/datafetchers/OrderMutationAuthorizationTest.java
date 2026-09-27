package ee.bytecore.backend.graphql.datafetchers;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import ee.bytecore.backend.config.SecurityConfig;
import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.graphql.datafetchers.order.OrderMutation;
import ee.bytecore.backend.graphql.datafetchers.order.OrderQuery;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.cart.CartItemRepository;
import ee.bytecore.backend.repositories.cart.CartRepository;
import ee.bytecore.backend.repositories.payment.OrderItemRepository;
import ee.bytecore.backend.repositories.payment.OrderRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.CartService;
import ee.bytecore.backend.services.OrderService;
import ee.bytecore.backend.services.OrderStatusPublisher;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves updateOrderStatus enforces @PreAuthorize("hasAnyRole('ORDER_MANAGER','ADMIN')")
 * against the real SecurityFilterChain (same pattern as CatalogMutationAuthorizationTest).
 */
@SpringBootTest(
        classes = {
            OrderQuery.class,
            OrderMutation.class,
            GraphQLConfig.class,
            LocalDateScalar.class,
            InstantScalar.class,
            SecurityConfig.class,
            OrderService.class,
            OrderStatusPublisher.class,
            CartService.class,
            CurrentUserProvider.class
        })
@EnableDgsMockMvcTest
class OrderMutationAuthorizationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    OrderRepository orderRepository;

    @MockitoBean
    OrderItemRepository orderItemRepository;

    @MockitoBean
    CartRepository cartRepository;

    @MockitoBean
    CartItemRepository cartItemRepository;

    @MockitoBean
    ProductVariantRepository productVariantRepository;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private Order order;

    @BeforeEach
    void setUp() {
        User user = User.create("owner", "owner@example.com", LocalDate.of(1990, 1, 1));
        user.setId(1L);
        order = Order.create(user, OrderStatus.PENDING, new BigDecimal("10.00"));
        order.setId(1L);
    }

    @Test
    void shouldRejectUpdateOrderStatusForNonOrderManagerTest() throws Exception {
        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt("2", "USER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { updateOrderStatus(orderId: \\\"1\\\", input: { status: PAID }) { status } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldAllowUpdateOrderStatusForOrderManagerTest() throws Exception {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt("2", "ORDER_MANAGER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { updateOrderStatus(orderId: \\\"1\\\", input: { status: PAID }) { status } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateOrderStatus.status").value("PAID"));
    }

    private RequestPostProcessor asAuthenticatedJwt(String subject, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .claim("role", role)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return authentication(jwtAuthenticationConverter.convert(jwt));
    }
}
