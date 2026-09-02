package ee.bytecore.backend.graphql.datafetchers;

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

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(classes = {CartQuery.class, CartMutation.class, GraphQLConfig.class, LocalDateScalar.class, InstantScalar.class})
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

    private User user;
    private Cart cart;
    private ProductVariant productVariant;
    private CartItem cartItem;

    @Autowired
    private GraphQlTester graphQlTester;

    @BeforeEach
    void setUp() {
        user = User.create("test-user", "test@example.com", "hashed-password", LocalDate.of(1995, 6, 15));
        cart = Cart.create(user);
        Product product = Product.create("T-Shirt", "t-shirt", "A plain t-shirt");
        productVariant = ProductVariant.create(product, "TSHIRT-M-BLACK", new BigDecimal("19.99"));
        cartItem = CartItem.create(cart, productVariant, 2);
    }

    @Test
    @WithMockUser(username = "test-user")
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
    @WithMockUser(username = "test-user")
    void shouldAddCartItemTest() {
        when(cartRepository.findByUserId(user.getId())).thenReturn(Optional.of(cart));
        when(productVariantRepository.findById(productVariant.getId())).thenReturn(Optional.of(productVariant));
        when(cartItemRepository.save(cartItem)).thenReturn(cartItem);

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
    @WithMockUser(username = "test-user")
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
    @WithMockUser(username = "test-user")
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
    @WithMockUser(username = "test-user")
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
    @WithMockUser(username = "test-user")
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
