package ee.bytecore.backend.graphql.datafetchers;

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

import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.wishlist.Wishlist;
import ee.bytecore.backend.entities.wishlist.WishlistItem;
import ee.bytecore.backend.graphql.datafetchers.wishlist.WishlistMutation;
import ee.bytecore.backend.graphql.datafetchers.wishlist.WishlistQuery;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.repositories.wishlist.WishlistItemRepository;
import ee.bytecore.backend.repositories.wishlist.WishlistRepository;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(
        classes = {
            WishlistQuery.class,
            WishlistMutation.class,
            GraphQLConfig.class,
            LocalDateScalar.class,
            InstantScalar.class,
            ee.bytecore.backend.services.WishlistService.class,
            ee.bytecore.backend.security.CurrentUserProvider.class
        })
@EnableDgsMockMvcTest
@AutoConfigureHttpGraphQlTester
@Tag("graphql")
class WishlistDataFetcherTest {

    @MockitoBean
    WishlistRepository wishlistRepository;

    @MockitoBean
    WishlistItemRepository wishlistItemRepository;

    @MockitoBean
    ProductVariantRepository productVariantRepository;

    @MockitoBean
    UserRepository userRepository;

    private User user;
    private Wishlist wishlist;
    private ProductVariant productVariant;
    private WishlistItem wishlistItem;

    @Autowired
    private GraphQlTester graphQlTester;

    @BeforeEach
    void setUp() {
        user = User.create("test-user", "test@example.com", LocalDate.of(1995, 6, 15));
        user.setId(1L);
        wishlist = Wishlist.create(user);
        wishlist.setId(1L);
        Product product = Product.create("T-Shirt", "t-shirt", "A plain t-shirt");
        product.setId(1L);
        productVariant = ProductVariant.create(product, "TSHIRT-M-BLACK", new BigDecimal("19.99"));
        productVariant.setId(1L);
        wishlistItem = WishlistItem.create(wishlist, productVariant);
        wishlistItem.setId(1L);
        when(userRepository.findActiveByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
    }

    @Test
    @WithMockUser(username = "1")
    void shouldReturnMyWishlistTest() {
        when(wishlistRepository.findByUserId(user.getId())).thenReturn(Optional.of(wishlist));

        @Language("GraphQl")
        var query =
                """
            query {
              myWishlist {
                items {
                  productVariant {
                    sku
                  }
                }
              }
            }
        """;

        graphQlTester
                .document(query)
                .execute()
                .path("myWishlist.items")
                .entityList(Object.class)
                .hasSize(0);
    }

    @Test
    @WithMockUser(username = "1")
    void shouldLazilyCreateWishlistForMyWishlistWhenMissingTest() {
        when(wishlistRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(userRepository.findActiveByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(wishlistRepository.saveAndFlush(any(Wishlist.class))).thenReturn(wishlist);

        @Language("GraphQl")
        var query =
                """
            query {
              myWishlist {
                items {
                  productVariant {
                    sku
                  }
                }
              }
            }
        """;

        graphQlTester
                .document(query)
                .execute()
                .path("myWishlist.items")
                .entityList(Object.class)
                .hasSize(0);
    }

    @Test
    @WithMockUser(username = "1")
    void shouldResolveWishlistForWishlistItemTest() {
        wishlist.getItems().add(wishlistItem);
        when(wishlistRepository.findByUserId(user.getId())).thenReturn(Optional.of(wishlist));
        when(wishlistItemRepository.findById(wishlistItem.getId())).thenReturn(Optional.of(wishlistItem));

        @Language("GraphQl")
        var query =
                """
            query {
              myWishlist {
                items {
                  wishlist {
                    id
                  }
                }
              }
            }
        """;

        graphQlTester
                .document(query)
                .execute()
                .path("myWishlist.items[0].wishlist.id")
                .entity(String.class)
                .isEqualTo(wishlist.getId().toString());
    }

    @Test
    @WithMockUser(username = "1")
    void shouldAddWishlistItemTest() {
        when(wishlistRepository.findByUserId(user.getId())).thenReturn(Optional.of(wishlist));
        when(productVariantRepository.findById(productVariant.getId())).thenReturn(Optional.of(productVariant));
        when(wishlistItemRepository.save(any(WishlistItem.class))).thenReturn(wishlistItem);

        @Language("GraphQl")
        var mutation =
                """
            mutation($variantId: ID!) {
              addWishlistItem(input: { productVariantId: $variantId }) {
                productVariant {
                  sku
                }
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("variantId", productVariant.getId())
                .execute()
                .path("addWishlistItem.productVariant.sku")
                .entity(String.class)
                .isEqualTo(productVariant.getSku());
    }

    @Test
    @WithMockUser(username = "1")
    void shouldRemoveWishlistItemTest() {
        Long itemId = wishlistItem.getId();
        when(wishlistItemRepository.findById(itemId)).thenReturn(Optional.of(wishlistItem));

        @Language("GraphQl")
        var mutation =
                """
            mutation($itemId: ID!) {
              removeWishlistItem(wishlistItemId: $itemId)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("itemId", itemId)
                .execute()
                .path("removeWishlistItem")
                .entity(Boolean.class)
                .isEqualTo(true);
    }

    @Test
    @WithMockUser(username = "1")
    void shouldRejectRemoveWishlistItemForAnotherUsersItemTest() {
        User otherUser = User.create("other-user", "other@example.com", LocalDate.of(1990, 1, 1));
        otherUser.setId(2L);
        Wishlist othersWishlist = Wishlist.create(otherUser);
        othersWishlist.setId(2L);
        WishlistItem othersItem = WishlistItem.create(othersWishlist, productVariant);
        othersItem.setId(2L);
        when(wishlistItemRepository.findById(2L)).thenReturn(Optional.of(othersItem));

        @Language("GraphQl")
        var mutation =
                """
            mutation($itemId: ID!) {
              removeWishlistItem(wishlistItemId: $itemId)
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
    void shouldReturnFalseWhenRemovingMissingWishlistItemTest() {
        Long itemId = 999L;
        when(wishlistItemRepository.findById(itemId)).thenReturn(Optional.empty());

        @Language("GraphQl")
        var mutation =
                """
            mutation($itemId: ID!) {
              removeWishlistItem(wishlistItemId: $itemId)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("itemId", itemId)
                .execute()
                .path("removeWishlistItem")
                .entity(Boolean.class)
                .isEqualTo(false);
    }
}
