package ee.bytecore.backend.graphql.datafetchers;

import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.graphql.scalars.GraphQLConfig;
import ee.bytecore.backend.graphql.scalars.InstantScalar;
import ee.bytecore.backend.graphql.scalars.LocalDateScalar;
import ee.bytecore.backend.repositories.user.UserRepository;

import com.netflix.graphql.dgs.test.EnableDgsMockMvcTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SpringBootTest(
    classes = {UserDataFetcher.class, GraphQLConfig.class, LocalDateScalar.class, InstantScalar.class})
@EnableDgsMockMvcTest
@AutoConfigureHttpGraphQlTester
@Tag("graphql")
class UserDataFetcherTest {

  @MockitoBean UserRepository userRepository;

  User user;
  @Autowired private GraphQlTester graphQlTester;

  @BeforeEach
  void setUp() {
    user = User.create("vlad28", "vlad@example.com", "hashed-password", LocalDate.of(1995, 6, 15));
  }

  @Test
  @WithMockUser
  void shouldReturnUserTest() throws Exception {
    Long id = user.getId();

    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    @Language("GraphQl")
    var query =
        """
        query($id: ID!) {
          user(id: id) {
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
        .isEqualTo(user.getDateOfBirth());
  }
}
