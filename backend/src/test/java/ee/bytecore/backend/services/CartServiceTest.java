package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import ee.bytecore.backend.entities.cart.Cart;
import ee.bytecore.backend.entities.cart.CartItem;
import ee.bytecore.backend.entities.product.Product;
import ee.bytecore.backend.entities.product.ProductVariant;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.repositories.cart.CartItemRepository;
import ee.bytecore.backend.repositories.cart.CartRepository;
import ee.bytecore.backend.repositories.product.ProductVariantRepository;
import ee.bytecore.backend.repositories.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * {@code self} is stubbed directly here to stand in for the {@code @Lazy}
 * self-injected proxy CartService uses to run cart creation in a new
 * transaction - no Spring context is needed to prove the get-or-create
 * behavior itself.
 */
@Tag("unit")
class CartServiceTest {

    @Mock
    CartRepository cartRepository;

    @Mock
    CartItemRepository cartItemRepository;

    @Mock
    ProductVariantRepository productVariantRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    CartService self;

    CartService cartService;

    User user;
    Cart cart;
    ProductVariant productVariant;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        cartService =
                new CartService(cartRepository, cartItemRepository, productVariantRepository, userRepository, self);

        user = User.create("test-user", "test@example.com", LocalDate.of(1995, 6, 15));
        user.setId(1L);
        cart = Cart.create(user);
        cart.setId(1L);

        Product product = Product.create("T-Shirt", "t-shirt", "A plain t-shirt");
        product.setId(1L);
        productVariant = ProductVariant.create(product, "TSHIRT-M-BLACK", new BigDecimal("19.99"));
        productVariant.setId(1L);
        when(userRepository.findActiveByIdForUpdate(1L)).thenReturn(Optional.of(user));
    }

    @Test
    void shouldReturnExistingCartWithoutCreatingOneTest() {
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));

        Cart result = cartService.getMyCart(1L);

        assertThat(result).isSameAs(cart);
        verify(self, never()).createCart(any());
    }

    @Test
    void shouldLazilyCreateCartWhenMissingTest() {
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(self.createCart(1L)).thenReturn(cart);

        Cart result = cartService.getMyCart(1L);

        assertThat(result).isSameAs(cart);
        verify(self, times(1)).createCart(1L);
    }

    @Test
    void shouldNotCreateDuplicateCartOnRepeatedCallsTest() {
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.empty(), Optional.of(cart));
        when(self.createCart(1L)).thenReturn(cart);

        cartService.getMyCart(1L);
        Cart second = cartService.getMyCart(1L);

        assertThat(second).isSameAs(cart);
        verify(self, times(1)).createCart(1L);
    }

    @Test
    void shouldReuseCartCreatedByConcurrentRequestWhenCreateRacesTest() {
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));

        Cart result = cartService.createCart(1L);

        assertThat(result).isSameAs(cart);
        var ordered = org.mockito.Mockito.inOrder(userRepository, cartRepository);
        ordered.verify(userRepository).findActiveByIdForUpdate(1L);
        ordered.verify(cartRepository).findByUserId(1L);
        verify(cartRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldLazilyCreateCartWhenAddingItemToMissingCartTest() {
        when(cartRepository.findByUserIdForUpdate(1L)).thenReturn(Optional.of(cart));
        when(self.createCart(1L)).thenReturn(cart);
        when(productVariantRepository.findById(1L)).thenReturn(Optional.of(productVariant));
        when(cartItemRepository.findByCartIdAndProductVariantId(1L, 1L)).thenReturn(Optional.empty());
        when(cartItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var item = cartService.addItem(1L, 1L, 2);

        assertThat(item.getCart()).isSameAs(cart);
        assertThat(item.getQuantity()).isEqualTo(2);
        verify(self, times(1)).createCart(1L);
    }

    @Test
    void shouldMergeQuantityWhenItemAlreadyInCartTest() {
        CartItem existing = CartItem.create(cart, productVariant, 2);
        existing.setId(5L);
        when(cartRepository.findByUserIdForUpdate(1L)).thenReturn(Optional.of(cart));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(productVariantRepository.findById(1L)).thenReturn(Optional.of(productVariant));
        when(cartItemRepository.findByCartIdAndProductVariantId(1L, 1L)).thenReturn(Optional.of(existing));
        when(cartItemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var item = cartService.addItem(1L, 1L, 3);

        assertThat(item.getId()).isEqualTo(5L);
        assertThat(item.getQuantity()).isEqualTo(5);
        verify(cartItemRepository, never()).save(argThat(saved -> saved != existing));
    }
}
