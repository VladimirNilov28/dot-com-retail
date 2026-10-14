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
import ee.bytecore.backend.entities.user.UserAddress;
import ee.bytecore.backend.repositories.user.UserAddressRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AddressesByUserIdDataLoaderTest {

    @Mock
    UserAddressRepository userAddressRepository;

    AddressesByUserIdDataLoader loader;

    private UserAddress addressOfUserOne;

    @BeforeEach
    void setUp() {
        loader = new AddressesByUserIdDataLoader(userAddressRepository);

        User userOne = User.create("user-one", "one@example.com", LocalDate.of(1995, 6, 15));
        userOne.setId(1L);
        addressOfUserOne = UserAddress.create(userOne, "John", "Smith", "NYC", "USA", "10001", "1 Main St", null, null);
        addressOfUserOne.setId(100L);
    }

    @Test
    void shouldBatchLookupInOneRepositoryCallAndMapToCorrectKeysTest() throws Exception {
        when(userAddressRepository.findAllByUserIdIn(anyList())).thenReturn(List.of(addressOfUserOne));

        Map<Long, List<UserAddress>> result =
                loader.load(Set.of(1L, 2L)).toCompletableFuture().get();

        verify(userAddressRepository, times(1)).findAllByUserIdIn(anyList());
        assertThat(result.get(1L)).containsExactly(addressOfUserOne);
        assertThat(result.get(2L)).isEmpty();
    }
}
