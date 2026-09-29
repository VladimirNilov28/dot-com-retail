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
import ee.bytecore.backend.repositories.wishlist.WishlistItemRepository;
import ee.bytecore.backend.repositories.wishlist.WishlistRepository;
import ee.bytecore.backend.security.CurrentUserProvider;
import ee.bytecore.backend.services.WishlistService;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves myWishlist/addWishlistItem/removeWishlistItem enforce the
 * wishlist:read/wishlist:write scope (self-service — no role requirement;
 * ownership is enforced in WishlistService, unaffected by this test) against the
 * real SecurityFilterChain + JwtAuthenticationConverter.
 */
@SpringBootTest(
        classes = {
            WishlistQuery.class,
            WishlistMutation.class,
            GraphQLConfig.class,
            LocalDateScalar.class,
            InstantScalar.class,
            SecurityConfig.class,
            WishlistService.class,
            CurrentUserProvider.class
        })
@EnableDgsMockMvcTest
class WishlistMutationAuthorizationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    WishlistRepository wishlistRepository;

    @MockitoBean
    WishlistItemRepository wishlistItemRepository;

    @MockitoBean
    ProductVariantRepository productVariantRepository;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private User user;
    private Wishlist wishlist;
    private ProductVariant productVariant;
    private WishlistItem wishlistItem;

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
    }

    @Test
    void shouldRejectMyWishlistMissingReadScopeTest() throws Exception {
        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "1", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ myWishlist { items { id } } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldAllowMyWishlistWithReadScopeTest() throws Exception {
        when(wishlistRepository.findByUserId(1L)).thenReturn(Optional.of(wishlist));

        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "1", "USER", "wishlist:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ myWishlist { items { id } } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myWishlist").exists());
    }

    @Test
    void shouldRejectAddWishlistItemMissingWriteScopeTest() throws Exception {
        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "1", "USER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { addWishlistItem(input: { productVariantId: \\\"1\\\" }) { id } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void shouldAllowAddWishlistItemWithWriteScopeTest() throws Exception {
        when(wishlistRepository.findByUserId(1L)).thenReturn(Optional.of(wishlist));
        when(productVariantRepository.findById(1L)).thenReturn(Optional.of(productVariant));
        when(wishlistItemRepository.save(org.mockito.ArgumentMatchers.any(WishlistItem.class)))
                .thenReturn(wishlistItem);

        mockMvc.perform(
                        post("/graphql")
                                .with(asAuthenticatedJwt(jwtAuthenticationConverter, "1", "USER", "wishlist:write"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"query\":\"mutation { addWishlistItem(input: { productVariantId: \\\"1\\\" }) { id } }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.addWishlistItem.id").value("1"));
    }

    @Test
    void shouldRejectRemoveWishlistItemForForeignWishlistWithWriteScopeTest() throws Exception {
        // Ownership is enforced in WishlistService regardless of the scope check —
        // a valid wishlist:write scope must not let user "2" remove user 1's item.
        when(wishlistItemRepository.findById(1L)).thenReturn(Optional.of(wishlistItem));

        mockMvc.perform(post("/graphql")
                        .with(asAuthenticatedJwt(jwtAuthenticationConverter, "2", "USER", "wishlist:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mutation { removeWishlistItem(wishlistItemId: \\\"1\\\") }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }
}
