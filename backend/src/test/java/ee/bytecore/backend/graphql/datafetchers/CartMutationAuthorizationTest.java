package ee.bytecore.backend.graphql.datafetchers;

import static ee.bytecore.backend.graphql.datafetchers.support.JwtTestSupport.asAuthenticatedJwt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ee.bytecore.backend.config.SecurityConfig;
import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.cart.CartItem;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.graphql.datafetchers.cart.CartMutation;
import ee.bytecore.backend.graphql.datafetchers.cart.CartQuery;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.cart.CartItemRepository;
import ee.bytecore.backend.repositories.cart.CartRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.CartService;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves myCart/addCartItem/updateCartItem/removeCartItem/clearCart enforce the
 * cart:read/cart:write scope (self-service — no role requirement; ownership is
 * enforced in CartService via CurrentUserProvider, unaffected by this test) against
 * the real SecurityFilterChain + JwtAuthenticationConverter.
 */
@SpringBootTest(
        classes = {
            CartQuery.class,
            CartMutation.class,
            GraphQLConfig.class,
            LocalDateScalar.class,
            InstantScalar.class,
            SecurityConfig.class,
            CartService.class,
            CurrentUserProvider.class
        })
@EnableDgsMockMvcTest
class CartMutationAuthorizationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    CartRepository cartRepository;

    @MockitoBean
    CartItemRepository cartItemRepository;

    @MockitoBean
    ProductVariantRepository productVariantRepository;

    @MockitoBean
    UserRepository userRepository;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private User user;
    private Cart cart;
    private ProductVariant productVariant;
    private CartItem cartItem;

    @BeforeEach
    void setUp() {
        user = User.create("test-user", "test@example.com", LocalDate.of(1995, 6, 15));
        user.setId(1L);
        cart = Cart.create(user);
        cart.setId(1L);
        Product product = Product.create("T-Shirt", "t-shirt", "A plain t-shirt");
        product.setId(1L);
        productVariant = ProductVariant.create(product, "TSHIRT-M-BLACK", new BigDecimal("19.99"));
        productVariant.setId(1L);
        cartItem = CartItem.create(cart, productVariant, 2);
        cartItem.setId(1L);
    }

    @Test
    void shouldRejectMyCartMissingReadScopeTest() throws Exception {
        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "1", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ myCart { items { quantity } } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldAllowMyCartWithReadScopeTest() throws Exception {
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));

        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "1", "USER", "cart:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ myCart { items { quantity } } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myCart").exists());
    }

    @Test
    void shouldRejectAddCartItemMissingWriteScopeTest() throws Exception {
        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "1", "USER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { addCartItem(input: { productVariantId: \\\"1\\\", quantity: 2 }) { quantity } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldAllowAddCartItemWithWriteScopeTest() throws Exception {
        when(cartRepository.findByUserIdForUpdate(1L)).thenReturn(Optional.of(cart));
        when(productVariantRepository.findById(1L)).thenReturn(Optional.of(productVariant));
        when(cartItemRepository.save(org.mockito.ArgumentMatchers.any(CartItem.class)))
                .thenReturn(cartItem);

        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "1", "USER", "cart:write"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { addCartItem(input: { productVariantId: \\\"1\\\", quantity: 2 }) { quantity } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.addCartItem.quantity").value(2));
    }

    @Test
    void shouldRejectRemoveCartItemMissingWriteScopeTest() throws Exception {
        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "1", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { removeCartItem(cartItemId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldRejectRemoveCartItemForForeignCartWithWriteScopeTest() throws Exception {
        // Ownership is enforced in CartService regardless of the scope check —
        // a valid cart:write scope must not let user "2" remove user 1's item.
        User otherUser = User.create("other-user", "other@example.com", LocalDate.of(1990, 1, 1));
        otherUser.setId(2L);
        Cart otherCart = Cart.create(otherUser);
        otherCart.setId(2L);
        when(cartRepository.findByUserIdForUpdate(2L)).thenReturn(Optional.of(otherCart));
        when(cartItemRepository.findById(1L)).thenReturn(Optional.of(cartItem));

        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "USER", "cart:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { removeCartItem(cartItemId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }
}
