package ee.bytecore.backend.graphql.datafetchers;

import static org.mockito.ArgumentMatchers.any;
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
import ee.bytecore.backend.enums.PaymentMethodType;
import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.graphql.user.UserDataFetcher;
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
        user = User.create("test-user", "test@example.com", "hashed-password", LocalDate.of(1995, 6, 15));

        userAddress = UserAddress.create(
                user, "John", "Smith", "New York", "USA", "10001", "123 Fake Street", null, "1-800-444-4444");

        userPaymentMethod = UserPaymentMethod.create(user, "mastercard", PaymentMethodType.CARD);

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.findByUsername("test-user")).thenReturn(Optional.of(user));
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
                .isEqualTo(userPaymentMethod.getType().name());
    }

    @Test
    @WithMockUser(username = "test-user")
    void shouldReturnMeTest() {
        @Language("GraphQl")
        var query =
                """
            query {
              me {
                username
                email
              }
            }
        """;

        graphQlTester
                .document(query)
                .execute()
                .path("me.username")
                .entity(String.class)
                .isEqualTo(user.getUsername())
                .path("me.email")
                .entity(String.class)
                .isEqualTo(user.getEmail());
    }

    @Test
    @WithMockUser
    void shouldCreateUserTest() {
        when(userRepository.save(any(User.class))).thenReturn(user);

        @Language("GraphQl")
        var mutation =
                """
            mutation {
              createUser(input: {
                username: "test-user"
                email: "test@example.com"
                password: "super-secret"
                dateOfBirth: "1995-06-15"
              }) {
                username
                email
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .execute()
                .path("createUser.username")
                .entity(String.class)
                .isEqualTo(user.getUsername());
    }

    @Test
    @WithMockUser(username = "test-user")
    void shouldAddMyAddressTest() {
        when(userAddressRepository.save(any(UserAddress.class))).thenReturn(userAddress);

        @Language("GraphQl")
        var mutation =
                """
            mutation {
              addMyAddress(input: { firstName: "John", lastName: "Smith", city: "New York" }) {
                firstName
                city
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .execute()
                .path("addMyAddress.firstName")
                .entity(String.class)
                .isEqualTo(userAddress.getFirstName());
    }

    @Test
    @WithMockUser(username = "test-user")
    void shouldUpdateMyAddressTest() {
        Long addressId = userAddress.getId();
        when(userAddressRepository.findById(addressId)).thenReturn(Optional.of(userAddress));
        when(userAddressRepository.save(userAddress)).thenReturn(userAddress);

        @Language("GraphQl")
        var mutation =
                """
            mutation($addressId: ID!) {
              updateMyAddress(addressId: $addressId, input: { city: "Boston" }) {
                city
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("addressId", addressId)
                .execute()
                .path("updateMyAddress.city")
                .entity(String.class)
                .isEqualTo("Boston");
    }

    @Test
    @WithMockUser(username = "test-user")
    void shouldDeleteMyAddressTest() {
        Long addressId = userAddress.getId();
        when(userAddressRepository.findById(addressId)).thenReturn(Optional.of(userAddress));

        @Language("GraphQl")
        var mutation =
                """
            mutation($addressId: ID!) {
              deleteMyAddress(addressId: $addressId)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("addressId", addressId)
                .execute()
                .path("deleteMyAddress")
                .entity(Boolean.class)
                .isEqualTo(true);
    }

    @Test
    @WithMockUser(username = "test-user")
    void shouldReturnFalseWhenDeletingMissingMyAddressTest() {
        Long addressId = 999L;
        when(userAddressRepository.findById(addressId)).thenReturn(Optional.empty());

        @Language("GraphQl")
        var mutation =
                """
            mutation($addressId: ID!) {
              deleteMyAddress(addressId: $addressId)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("addressId", addressId)
                .execute()
                .path("deleteMyAddress")
                .entity(Boolean.class)
                .isEqualTo(false);
    }

    @Test
    @WithMockUser(username = "test-user")
    void shouldAddMyPaymentMethodTest() {
        when(userPaymentMethodRepository.save(any(UserPaymentMethod.class))).thenReturn(userPaymentMethod);

        @Language("GraphQl")
        var mutation =
                """
            mutation {
              addMyPaymentMethod(input: { method: "mastercard", type: CARD }) {
                provider
                type
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .execute()
                .path("addMyPaymentMethod.type")
                .entity(String.class)
                .isEqualTo(userPaymentMethod.getType().name());
    }

    @Test
    @WithMockUser(username = "test-user")
    void shouldDeleteMyPaymentMethodTest() {
        Long paymentMethodId = userPaymentMethod.getId();
        when(userPaymentMethodRepository.findById(paymentMethodId)).thenReturn(Optional.of(userPaymentMethod));

        @Language("GraphQl")
        var mutation =
                """
            mutation($paymentMethodId: ID!) {
              deleteMyPaymentMethod(paymentMethodId: $paymentMethodId)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("paymentMethodId", paymentMethodId)
                .execute()
                .path("deleteMyPaymentMethod")
                .entity(Boolean.class)
                .isEqualTo(true);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldAddUserAddressTest() {
        Long userId = user.getId();
        when(userAddressRepository.save(any(UserAddress.class))).thenReturn(userAddress);

        @Language("GraphQl")
        var mutation =
                """
            mutation($userId: ID!) {
              addUserAddress(userId: $userId, input: { firstName: "John", city: "New York" }) {
                firstName
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("userId", userId)
                .execute()
                .path("addUserAddress.firstName")
                .entity(String.class)
                .isEqualTo(userAddress.getFirstName());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldUpdateUserAddressTest() {
        Long userId = user.getId();
        Long addressId = userAddress.getId();
        when(userAddressRepository.findById(addressId)).thenReturn(Optional.of(userAddress));
        when(userAddressRepository.save(userAddress)).thenReturn(userAddress);

        @Language("GraphQl")
        var mutation =
                """
            mutation($userId: ID!, $addressId: ID!) {
              updateUserAddress(userId: $userId, addressId: $addressId, input: { city: "Boston" }) {
                city
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("userId", userId)
                .variable("addressId", addressId)
                .execute()
                .path("updateUserAddress.city")
                .entity(String.class)
                .isEqualTo("Boston");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldDeleteUserAddressTest() {
        Long userId = user.getId();
        Long addressId = userAddress.getId();
        when(userAddressRepository.findById(addressId)).thenReturn(Optional.of(userAddress));

        @Language("GraphQl")
        var mutation =
                """
            mutation($userId: ID!, $addressId: ID!) {
              deleteUserAddress(userId: $userId, addressId: $addressId)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("userId", userId)
                .variable("addressId", addressId)
                .execute()
                .path("deleteUserAddress")
                .entity(Boolean.class)
                .isEqualTo(true);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldUpdateUserRoleTest() {
        Long userId = user.getId();
        when(userRepository.save(user)).thenReturn(user);

        @Language("GraphQl")
        var mutation =
                """
            mutation($userId: ID!) {
              updateUserRole(userId: $userId, input: { role: SUPPORT }) {
                role
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("userId", userId)
                .execute()
                .path("updateUserRole.role")
                .entity(String.class)
                .isEqualTo(UserRole.SUPPORT.name());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldAddUserPaymentMethodTest() {
        Long userId = user.getId();
        when(userPaymentMethodRepository.save(any(UserPaymentMethod.class))).thenReturn(userPaymentMethod);

        @Language("GraphQl")
        var mutation =
                """
            mutation($userId: ID!) {
              addUserPaymentMethod(userId: $userId, input: { method: "mastercard", type: CARD }) {
                type
              }
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("userId", userId)
                .execute()
                .path("addUserPaymentMethod.type")
                .entity(String.class)
                .isEqualTo(userPaymentMethod.getType().name());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldDeleteUserPaymentMethodTest() {
        Long userId = user.getId();
        Long paymentMethodId = userPaymentMethod.getId();
        when(userPaymentMethodRepository.findById(paymentMethodId)).thenReturn(Optional.of(userPaymentMethod));

        @Language("GraphQl")
        var mutation =
                """
            mutation($userId: ID!, $paymentMethodId: ID!) {
              deleteUserPaymentMethod(userId: $userId, paymentMethodId: $paymentMethodId)
            }
        """;

        graphQlTester
                .document(mutation)
                .variable("userId", userId)
                .variable("paymentMethodId", paymentMethodId)
                .execute()
                .path("deleteUserPaymentMethod")
                .entity(Boolean.class)
                .isEqualTo(true);
    }
}
