package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;

import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.wishlist.Wishlist;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.repositories.wishlist.WishlistItemRepository;
import ee.bytecore.backend.repositories.wishlist.WishlistRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * {@code self} is stubbed directly here to stand in for the {@code @Lazy}
 * self-injected proxy WishlistService uses to run wishlist creation in a new
 * transaction - mirrors {@link CartServiceTest}'s get-or-create test style,
 * no Spring context needed.
 */
@Tag("unit")
class WishlistServiceTest {

    @Mock
    WishlistRepository wishlistRepository;

    @Mock
    WishlistItemRepository wishlistItemRepository;

    @Mock
    ProductVariantRepository productVariantRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    WishlistService self;

    WishlistService wishlistService;

    User user;
    Wishlist wishlist;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        wishlistService = new WishlistService(
                wishlistRepository, wishlistItemRepository, productVariantRepository, userRepository, self);

        user = User.create("test-user", "test@example.com", LocalDate.of(1995, 6, 15));
        user.setId(1L);
        wishlist = Wishlist.create(user);
        wishlist.setId(1L);
    }

    @Test
    void shouldReturnExistingWishlistWithoutCreatingOneTest() {
        when(wishlistRepository.findByUserId(1L)).thenReturn(Optional.of(wishlist));

        Wishlist result = wishlistService.getMyWishlist(1L);

        assertThat(result).isSameAs(wishlist);
        verify(self, never()).createWishlist(any());
    }

    @Test
    void shouldLazilyCreateWishlistWhenMissingTest() {
        when(wishlistRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(self.createWishlist(1L)).thenReturn(wishlist);

        Wishlist result = wishlistService.getMyWishlist(1L);

        assertThat(result).isSameAs(wishlist);
        verify(self, times(1)).createWishlist(1L);
    }

    @Test
    void shouldNotCreateDuplicateWishlistOnRepeatedCallsTest() {
        when(wishlistRepository.findByUserId(1L)).thenReturn(Optional.empty(), Optional.of(wishlist));
        when(self.createWishlist(1L)).thenReturn(wishlist);

        wishlistService.getMyWishlist(1L);
        Wishlist second = wishlistService.getMyWishlist(1L);

        assertThat(second).isSameAs(wishlist);
        verify(self, times(1)).createWishlist(1L);
    }

    @Test
    void shouldReuseWishlistCreatedByConcurrentRequestWhenCreateRacesTest() {
        when(wishlistRepository.findByUserId(1L)).thenReturn(Optional.empty(), Optional.of(wishlist));
        when(self.createWishlist(1L)).thenThrow(new DataIntegrityViolationException("duplicate key"));

        Wishlist result = wishlistService.getMyWishlist(1L);

        assertThat(result).isSameAs(wishlist);
    }
}
