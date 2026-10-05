package ee.bytecore.backend.graphql.datafetchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(
        classes = {
            CartQuery.class,
            CartMutation.class,
            GraphQLConfig.class,
            ee.bytecore.backend.graphql.GraphQlExceptionResolver.class,
            LocalDateScalar.class,
            InstantScalar.class,
            ee.bytecore.backend.services.CartService.class,
            ee.bytecore.backend.security.CurrentUserProvider.class
        })
@EnableDgsMockMvcTest
@AutoConfigureHttpGraphQlTester
@Tag("graphql")
class CartDataFetcherTest {

    @MockitoBean
    CartRepository cartRepository;

    @MockitoBean
    CartItemRepository cartItemRepository;

    @MockitoBean
    ProductVariantRepository productVariantRepository;

    @MockitoBean
    UserRepository userRepository;

    private User user;
    private Cart cart;
    private ProductVariant productVariant;
    private CartItem cartItem;

    @Autowired
    private GraphQlTester graphQlTester;

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
        when(cartRepository.findByUserIdForUpdate(user.getId())).thenReturn(Optional.of(cart));
        when(userRepository.findActiveByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(user.getId())).thenReturn(Optional.of(cart));
    }

    @Test
    @WithMockUser(username = "1")
    void shouldReturnMyCartTest() {
        when(cartRepository.findByUserId(user.getId())).thenReturn(Optional.of(cart));

        @Language("GraphQl")
        var query =
                """
            query {
              myCart {
                items {
                  quantity
                }
              }
            }
        """;

        graphQlTester
                .document(query)
                .execute()
                .path("myCart.items")
                .entityList(Object.class)
                .hasSize(0);
    }

    @Test
    @WithMockUser(username = "1")
    void shouldResolveCartForCartItemTest() {
        cart.getItems().add(cartItem);
        when(cartRepository.findByUserId(user.getId())).thenReturn(Optional.of(cart));
        when(cartItemRepository.findById(cartItem.getId())).thenReturn(Optional.of(cartItem));

        @Language("GraphQl")
        var query =
                """
            query {
              myCart {
                items {
                  cart {
                    id
                  }
                }
              }
            }
        """;

        graphQlTester
                .document(query)
                .execute()
                .path("myCart.items[0].cart.id")
                .entity(String.class)
                .isEqualTo(cart.getId().toString());
    }

    @Test
    @WithMockUser(username = "1")
    void shouldLazilyCreateCartForMyCartWhenMissingTest() {
        when(cartRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(cartRepository.saveAndFlush(any(Cart.class))).thenReturn(cart);

        @Language("GraphQl")
        var query =
                """
            query {
              myCart {
                items {
                  quantity
                }
              }
            }
        """;

        graphQlTester
                .document(query)
                .execute()
                .path("myCart.items")
                .entityList(Object.class)
                .hasSize(0);
    }

    @Test
    @WithMockUser(username = "1")
    void shouldLazilyCreateCartForAddCartItemWhenMissingTest() {
        when(cartRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(cartRepository.findByUserIdForUpdate(user.getId())).thenReturn(Optional.of(cart));
        when(cartRepository.saveAndFlush(any(Cart.class))).thenReturn(cart);
        when(productVariantRepository.findById(productVariant.getId())).thenReturn(Optional.of(productVariant));
        when(cartItemRepository.save(any(CartItem.class))).thenReturn(cartItem);

        @Language("GraphQl")
        var mutation =
                """
            mutation($variantId: ID!) {
              addCartItem(input: { productVariantId: $variantId, quantity: 2 }) {
                quantity
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("variantId", productVariant.getId())
                .execute()
                .path("addCartItem.quantity")
                .entity(Integer.class)
                .isEqualTo(2);
    }

    @Test
    @WithMockUser(username = "1")
    void shouldAddCartItemTest() {
        when(cartRepository.findByUserId(user.getId())).thenReturn(Optional.of(cart));
        when(productVariantRepository.findById(productVariant.getId())).thenReturn(Optional.of(productVariant));
        when(cartItemRepository.save(any(CartItem.class))).thenReturn(cartItem);

        @Language("GraphQl")
        var mutation =
                """
            mutation($variantId: ID!) {
              addCartItem(input: { productVariantId: $variantId, quantity: 2 }) {
                quantity
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("variantId", productVariant.getId())
                .execute()
                .path("addCartItem.quantity")
                .entity(Integer.class)
                .isEqualTo(2);
    }

    @Test
    @WithMockUser(username = "1")
    void shouldRejectAddCartItemWithNegativeQuantityTest() {
        when(cartRepository.findByUserId(user.getId())).thenReturn(Optional.of(cart));
        when(productVariantRepository.findById(productVariant.getId())).thenReturn(Optional.of(productVariant));
        when(cartItemRepository.save(any(CartItem.class))).thenReturn(cartItem);

        @Language("GraphQl")
        var mutation =
                """
            mutation($variantId: ID!) {
              addCartItem(input: { productVariantId: $variantId, quantity: -1 }) {
                quantity
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("variantId", productVariant.getId())
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .as("a negative quantity must be rejected")
                        .isNotEmpty());
    }

    @Test
    @WithMockUser(username = "1")
    void shouldRejectAddCartItemWithZeroQuantityTest() {
        when(cartRepository.findByUserId(user.getId())).thenReturn(Optional.of(cart));
        when(productVariantRepository.findById(productVariant.getId())).thenReturn(Optional.of(productVariant));
        when(cartItemRepository.save(any(CartItem.class))).thenReturn(cartItem);

        @Language("GraphQl")
        var mutation =
                """
            mutation($variantId: ID!) {
              addCartItem(input: { productVariantId: $variantId, quantity: 0 }) {
                quantity
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("variantId", productVariant.getId())
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .as("a zero quantity must be rejected")
                        .isNotEmpty());
    }

    @Test
    @WithMockUser(username = "1")
    void shouldRejectUpdateCartItemQuantityToNegativeTest() {
        Long itemId = cartItem.getId();
        when(cartItemRepository.findById(itemId)).thenReturn(Optional.of(cartItem));
        when(cartItemRepository.save(cartItem)).thenReturn(cartItem);

        @Language("GraphQl")
        var mutation =
                """
            mutation($itemId: ID!) {
              updateCartItem(cartItemId: $itemId, input: { quantity: -3 }) {
                quantity
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("itemId", itemId)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .as("a negative quantity must be rejected")
                        .isNotEmpty());
    }

    @Test
    @WithMockUser(username = "1")
    void shouldUpdateCartItemTest() {
        Long itemId = cartItem.getId();
        when(cartItemRepository.findById(itemId)).thenReturn(Optional.of(cartItem));
        when(cartItemRepository.save(cartItem)).thenReturn(cartItem);

        @Language("GraphQl")
        var mutation =
                """
            mutation($itemId: ID!) {
              updateCartItem(cartItemId: $itemId, input: { quantity: 5 }) {
                quantity
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("itemId", itemId)
                .execute()
                .path("updateCartItem.quantity")
                .entity(Integer.class)
                .isEqualTo(5);
    }

    @Test
    @WithMockUser(username = "1")
    void shouldRejectUpdateCartItemForAnotherUsersItemTest() {
        User otherUser = User.create("other-user", "other@example.com", LocalDate.of(1990, 1, 1));
        otherUser.setId(2L);
        Cart othersCart = Cart.create(otherUser);
        othersCart.setId(2L);
        CartItem othersItem = CartItem.create(othersCart, productVariant, 1);
        othersItem.setId(2L);
        when(cartItemRepository.findById(2L)).thenReturn(Optional.of(othersItem));

        @Language("GraphQl")
        var mutation =
                """
            mutation($itemId: ID!) {
              updateCartItem(cartItemId: $itemId, input: { quantity: 5 }) {
                quantity
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("itemId", 2L)
                .execute()
                .errors()
                .satisfy(errors ->
                        org.assertj.core.api.Assertions.assertThat(errors).isNotEmpty());
    }

    @Test
    @WithMockUser(username = "1")
    void shouldRemoveCartItemTest() {
        Long itemId = cartItem.getId();
        when(cartItemRepository.findById(itemId)).thenReturn(Optional.of(cartItem));

        @Language("GraphQl")
        var mutation =
                """
            mutation($itemId: ID!) {
              removeCartItem(cartItemId: $itemId)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("itemId", itemId)
                .execute()
                .path("removeCartItem")
                .entity(Boolean.class)
                .isEqualTo(true);
    }

    @Test
    @WithMockUser(username = "1")
    void shouldReturnFalseWhenRemovingMissingCartItemTest() {
        Long itemId = 999L;
        when(cartItemRepository.findById(itemId)).thenReturn(Optional.empty());

        @Language("GraphQl")
        var mutation =
                """
            mutation($itemId: ID!) {
              removeCartItem(cartItemId: $itemId)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("itemId", itemId)
                .execute()
                .path("removeCartItem")
                .entity(Boolean.class)
                .isEqualTo(false);
    }

    @Test
    @WithMockUser(username = "1")
    void shouldClearCartTest() {
        when(cartRepository.findByUserId(user.getId())).thenReturn(Optional.of(cart));

        @Language("GraphQl")
        var mutation = """
            mutation {
              clearCart
            }
        """;

        graphQlTester
                .document(mutation)
                .execute()
                .path("clearCart")
                .entity(Boolean.class)
                .isEqualTo(true);
    }
}
