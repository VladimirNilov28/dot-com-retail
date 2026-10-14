package ee.bytecore.backend.graphql.dataloaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.user.UserPaymentMethod;
import ee.bytecore.backend.enums.PaymentMethodType;
import ee.bytecore.backend.repositories.user.UserPaymentMethodRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentMethodsByUserIdDataLoaderTest {

    @Mock
    UserPaymentMethodRepository userPaymentMethodRepository;

    PaymentMethodsByUserIdDataLoader loader;

    private UserPaymentMethod paymentMethodOfUserOne;

    @BeforeEach
    void setUp() {
        loader = new PaymentMethodsByUserIdDataLoader(userPaymentMethodRepository);

        User userOne = User.create("user-one", "one@example.com", LocalDate.of(1995, 6, 15));
        userOne.setId(1L);
        paymentMethodOfUserOne = UserPaymentMethod.create(userOne, "mastercard", PaymentMethodType.CARD);
        paymentMethodOfUserOne.setId(100L);
    }

    @Test
    void shouldBatchLookupInOneRepositoryCallAndMapToCorrectKeysTest() throws Exception {
        when(userPaymentMethodRepository.findAllByUserIdIn(anyList())).thenReturn(List.of(paymentMethodOfUserOne));

        Map<Long, List<UserPaymentMethod>> result =
                loader.load(Set.of(1L, 2L)).toCompletableFuture().get();

        verify(userPaymentMethodRepository, times(1)).findAllByUserIdIn(anyList());
        assertThat(result.get(1L)).containsExactly(paymentMethodOfUserOne);
        assertThat(result.get(2L)).isEmpty();
    }
}
