package ee.bytecore.backend.graphql.datafetchers;

import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.user.UserAddress;
import ee.bytecore.backend.entities.user.UserPaymentMethod;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.user.UserAddressRepository;
import ee.bytecore.backend.repositories.user.UserPaymentMethodRepository;
import ee.bytecore.backend.repositories.user.UserRepository;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(classes = {UserDataFetcher.class, GraphQLConfig.class, LocalDateScalar.class, InstantScalar.class})
@EnableDgsMockMvcTest
@AutoConfigureHttpGraphQlTester
@Tag("graphql")
class UserDataFetcherTest {

    @MockitoBean
    UserRepository userRepository;

    @MockitoBean
    UserAddressRepository userAddressRepository;

    @MockitoBean
    UserPaymentMethodRepository userPaymentMethodRepository;

    private User user;
    private UserAddress userAddress;
    private UserPaymentMethod userPaymentMethod;

    @Autowired
    private GraphQlTester graphQlTester;

    @BeforeEach
    void setUp() {
        user = User.create(
                "test-user",
                "test@example.com",
                "hashed-password",
                LocalDate.of(1995, 6, 15)
        );

        userAddress = UserAddress.create(
                user,
                "John",
                "Smith",
                "New York",
                "USA",
                "10001",
                "123 Fake Street",
                null,
                "1-800-444-4444"
        );

        userPaymentMethod = UserPaymentMethod.create(
                user,
                "mastercard",
                "card"
        );

        when(userRepository.findById(user.getId()))
                .thenReturn(Optional.of(user));
    }

    @Test
    @WithMockUser
    void shouldReturnUserTest() throws Exception {
        Long id = user.getId();

        @Language("GraphQl")
        var query =
                """
            query($id: ID!) {
              user(id: $id) {
                username
                email
                dateOfBirth
              }
            }
        """;

        graphQlTester
                .document(query)
                .variable("id", id)
                .execute()
                .path("user.username")
                .entity(String.class)
                .isEqualTo(user.getUsername())
                .path("user.email")
                .entity(String.class)
                .isEqualTo(user.getEmail())
                .path("user.dateOfBirth")
                .entity(String.class)
                .isEqualTo(user.getDateOfBirth().toString());
    }

    @Test
    @WithMockUser
    void shouldReturnAddressTest() throws Exception {
        Long id = user.getId();

        when(userAddressRepository.findAllByUserId(id)).thenReturn(List.of(userAddress));

        @Language("GraphQl")
        var query =
                """
          query($id: ID!) {
            user(id: $id) {
              addresses {
                firstName
                lastName
                city
                country
                postalCode
                addressLine1
                addressLine2
                mobile
              }
            }
          }
        """;

        graphQlTester
                .document(query)
                .variable("id", id)
                .execute()
                .path("user.addresses[0].firstName")
                .entity(String.class)
                .isEqualTo(userAddress.getFirstName())
                .path("user.addresses[0].lastName")
                .entity(String.class)
                .isEqualTo(userAddress.getLastName())
                .path("user.addresses[0].city")
                .entity(String.class)
                .isEqualTo(userAddress.getCity())
                .path("user.addresses[0].country")
                .entity(String.class)
                .isEqualTo(userAddress.getCountry())
                .path("user.addresses[0].postalCode")
                .entity(String.class)
                .isEqualTo(userAddress.getPostalCode())
                .path("user.addresses[0].addressLine1")
                .entity(String.class)
                .isEqualTo(userAddress.getAddressLine1())
                .path("user.addresses[0].addressLine2")
                .valueIsNull()
                .path("user.addresses[0].mobile")
                .entity(String.class)
                .isEqualTo(userAddress.getMobile());
    }

    @Test
    @WithMockUser
    void shouldReturnPaymentMethodTest() throws Exception {
        Long id = user.getId();

        when(userPaymentMethodRepository.findAllByUserId(id)).thenReturn(List.of(userPaymentMethod));

        @Language("GraphQl")
        var query =
                """
          query($id: ID!) {
            user(id: $id) {
              paymentMethods {
                provider
                type
              }
            }
          }
        """;

        graphQlTester
                .document(query)
                .variable("id", id)
                .execute()
                .path("user.paymentMethods[0].provider")
                .entity(String.class)
                .isEqualTo(userPaymentMethod.getProvider())
                .path("user.paymentMethods[0].type")
                .entity(String.class)
                .isEqualTo(userPaymentMethod.getType());
    }
}
