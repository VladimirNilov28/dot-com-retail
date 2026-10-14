package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.UserRole;
import ee.bytecore.backend.exceptions.UserAlreadyExistsException;
import ee.bytecore.backend.integration.HydraClient;
import ee.bytecore.backend.integration.KratosClient;
import ee.bytecore.backend.repositories.user.UserRepository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
@SpringBootTest(
        classes = BootstrapAdminIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties =
                "spring.autoconfigure.exclude=org.springframework.boot.graphql.autoconfigure.GraphQlAutoConfiguration,"
                        + "com.netflix.graphql.dgs.springgraphql.autoconfig.DgsSpringGraphQLAutoConfiguration")
class BootstrapAdminIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    @Import({UserService.class, PostgresTestConfiguration.class})
    static class TestConfig {}

    private static final LocalDate DOB = LocalDate.of(2000, 1, 1);

    @MockitoBean
    KratosClient kratosClient;

    @MockitoBean
    HydraClient hydraClient;

    @Autowired
    UserService userService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private String username() {
        return "bootstrap-" + UUID.randomUUID();
    }

    @Test
    void shouldPersistFreshAdminAndRejectRepeatedBootstrapTest() {
        String username = username();
        User created = userService.provisionBootstrapAdmin(username, username + "@example.com", DOB);
        assertThat(userRepository.findById(created.getId()).orElseThrow().getRole())
                .isEqualTo(UserRole.ADMIN);
        assertThatThrownBy(() -> userService.provisionBootstrapAdmin(username, username + "@example.com", DOB))
                .isInstanceOf(UserAlreadyExistsException.class);
        assertThat(userRepository.findById(created.getId()).orElseThrow().getRole())
                .isEqualTo(UserRole.ADMIN);
    }

    @Test
    void shouldNotElevatePreclaimedUsernameOrEmailTest() {
        String username = username();
        String email = username + "@example.com";
        User preclaimed = userRepository.saveAndFlush(User.create(username, email, DOB));
        assertThatThrownBy(() -> userService.provisionBootstrapAdmin(username, "other-" + email, DOB))
                .isInstanceOf(UserAlreadyExistsException.class);
        assertThatThrownBy(() -> userService.provisionBootstrapAdmin("other-" + username, email, DOB))
                .isInstanceOf(UserAlreadyExistsException.class);
        User retained = userRepository.findById(preclaimed.getId()).orElseThrow();
        assertThat(retained.getRole()).isEqualTo(UserRole.USER);
        assertThat(retained.getUsername()).isEqualTo(username);
        assertThat(retained.getEmail()).isEqualTo(email);
        assertThat(retained.getDateOfBirth()).isEqualTo(DOB);
    }

    @Test
    void shouldRejectRealUniqueRaceWithoutElevatingWinningCustomerTest() throws Exception {
        String username = username();
        String email = username + "@example.com";
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var registration = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                User user = userRepository.saveAndFlush(User.create(username, email, DOB));
                inserted.countDown();
                try {
                    assertThat(commit.await(30, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return user.getId();
            }));
            try {
                assertThat(inserted.await(20, TimeUnit.SECONDS)).isTrue();
                var bootstrap = executor.submit(() -> {
                    try {
                        userService.provisionBootstrapAdmin(username, email, DOB);
                        return null;
                    } catch (RuntimeException e) {
                        return e;
                    }
                });
                // Both pre-checks miss the uncommitted customer. Confirm the
                // actual bootstrap INSERT is waiting on the unique-key lock.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                            """
                            SELECT EXISTS (
                              SELECT 1 FROM pg_stat_activity
                              WHERE datname = current_database()
                                AND wait_event_type = 'Lock'
                                AND query ILIKE '%insert into users%'
                            )
                            """,
                            Boolean.class));
                    if (waiting) {
                        break;
                    }
                    Thread.sleep(25);
                }
                assertThat(waiting)
                        .as("bootstrap insert waits for the racing customer transaction")
                        .isTrue();
                commit.countDown();
                Long winnerId = registration.get(20, TimeUnit.SECONDS);
                assertThat(bootstrap.get(20, TimeUnit.SECONDS)).isInstanceOf(UserAlreadyExistsException.class);
                User winner = userRepository.findById(winnerId).orElseThrow();
                assertThat(winner.getRole()).isEqualTo(UserRole.USER);
                assertThat(winner.getEmail()).isEqualTo(email);
                assertThat(jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM users WHERE username = ? OR email = ?",
                                Long.class,
                                username,
                                email))
                        .isEqualTo(1L);
            } finally {
                commit.countDown();
            }
        }
    }
}
