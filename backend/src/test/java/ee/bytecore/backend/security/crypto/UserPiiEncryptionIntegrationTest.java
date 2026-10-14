package ee.bytecore.backend.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.user.UserRegistrationReservation;
import ee.bytecore.backend.exceptions.UserAlreadyExistsException;
import ee.bytecore.backend.repositories.user.UserRegistrationReservationRepository;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.services.UserService;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** V27: account email/date of birth are ciphertext at rest, found and kept unique through the keyed email index. */
@SpringBootTest(
        properties = {
            "spring.kafka.bootstrap-servers=127.0.0.1:1",
            "spring.kafka.listener.auto-startup=false",
            "spring.docker.compose.enabled=false",
            "order.mail-poll-ms=999999999",
            "management.health.mail.enabled=false"
        })
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class UserPiiEncryptionIntegrationTest {
    @Autowired
    UserRepository users;

    @Autowired
    UserRegistrationReservationRepository reservations;

    @Autowired
    UserService userService;

    @Autowired
    EncryptionBackfill backfill;

    @Autowired
    JdbcTemplate jdbc;

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 12);
    }

    @Test
    void newAccountsStoreCiphertextAndAreFoundCaseInsensitively() {
        String key = unique();
        String email = "Ada." + key + "@Example.test";
        User saved = userService.create("ada-" + key, email, LocalDate.of(1990, 4, 12));

        Map<String, Object> raw =
                jdbc.queryForMap("SELECT email, date_of_birth, email_index FROM users WHERE id = ?", saved.getId());
        assertThat((String) raw.get("email")).startsWith("enc:");
        assertThat((String) raw.get("date_of_birth")).startsWith("enc:");
        assertThat((String) raw.get("email_index")).startsWith(FieldCipher.USER_EMAIL_INDEX_VERSION + ":");
        assertThat(raw.toString()).doesNotContain(key, "1990-04-12");

        User loaded = users.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getEmail()).isEqualTo(email);
        assertThat(loaded.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 4, 12));
        assertThat(users.existsByEmail(email.toUpperCase())).isTrue();
        assertThat(users.existsByEmail(" " + email.toLowerCase() + " ")).isTrue();
        assertThat(users.existsByEmail("other-" + key + "@example.test")).isFalse();
    }

    @Test
    void emailUniquenessIsCaseInsensitiveInTheServiceAndTheDatabase() {
        String key = unique();
        userService.create("first-" + key, "dup." + key + "@example.test", LocalDate.of(1990, 1, 1));

        assertThatThrownBy(() ->
                        userService.create("second-" + key, "DUP." + key + "@EXAMPLE.test", LocalDate.of(1990, 1, 1)))
                .isInstanceOf(UserAlreadyExistsException.class);
        assertThatThrownBy(() -> users.saveAndFlush(
                        User.create("third-" + key, "Dup." + key + "@example.TEST", LocalDate.of(1990, 1, 1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void backfillEncryptsLegacyPlaintextAccountsIdempotently() {
        String key = unique();
        String email = "Legacy." + key + "@Example.test";
        Long id = jdbc.queryForObject(
                "INSERT INTO users (username, email, date_of_birth) VALUES (?, ?, '1985-05-05') RETURNING id",
                Long.class,
                "legacy-" + key,
                email);

        // Before the backfill the legacy row is still readable and still blocks duplicates.
        assertThat(users.existsByEmail(email.toLowerCase())).isTrue();
        assertThat(users.findById(id).orElseThrow().getDateOfBirth()).isEqualTo(LocalDate.of(1985, 5, 5));

        backfill.backfillAccounts("users");

        Map<String, Object> raw =
                jdbc.queryForMap("SELECT email, date_of_birth, email_index FROM users WHERE id = ?", id);
        assertThat((String) raw.get("email")).startsWith("enc:");
        assertThat((String) raw.get("date_of_birth")).startsWith("enc:");
        assertThat(raw.get("email_index")).isEqualTo(FieldEncryption.userEmailIndex(email));
        assertThat(raw.toString()).doesNotContain(key, "1985-05-05");
        User loaded = users.findById(id).orElseThrow();
        assertThat(loaded.getEmail()).isEqualTo(email);
        assertThat(loaded.getDateOfBirth()).isEqualTo(LocalDate.of(1985, 5, 5));
        assertThat(users.existsByEmail(email.toUpperCase())).isTrue();

        backfill.backfillAccounts("users");
        assertThat(jdbc.queryForMap("SELECT email, date_of_birth, email_index FROM users WHERE id = ?", id))
                .isEqualTo(raw);
        assertThatThrownBy(() -> userService.create("again-" + key, email.toUpperCase(), LocalDate.of(1990, 1, 1)))
                .isInstanceOf(UserAlreadyExistsException.class);
    }

    @Test
    void backfillLeavesACaseInsensitiveDuplicateReadableAndContinues() {
        String key = unique();
        userService.create("owner-" + key, "clash." + key + "@example.test", LocalDate.of(1990, 1, 1));
        Long legacy = jdbc.queryForObject(
                "INSERT INTO users (username, email, date_of_birth) VALUES (?, ?, '1970-02-02') RETURNING id",
                Long.class,
                "clash-" + key,
                "CLASH." + key + "@example.test");

        backfill.backfillAccounts("users");

        assertThat(jdbc.queryForObject("SELECT email_index IS NULL FROM users WHERE id = ?", Boolean.class, legacy))
                .isTrue();
        assertThat(users.findById(legacy).orElseThrow().getEmail()).isEqualTo("CLASH." + key + "@example.test");
    }

    @Test
    void registrationReservationsAreEncryptedAndLookedUpByIndex() {
        String key = unique();
        String email = "Reserve." + key + "@example.test";
        var reservation = reservations.saveAndFlush(UserRegistrationReservation.create(
                UUID.randomUUID(),
                "reserve-" + key,
                email,
                LocalDate.of(2000, 3, 3),
                Instant.now().plus(1, ChronoUnit.HOURS)));
        Map<String, Object> raw = jdbc.queryForMap(
                "SELECT email, date_of_birth, email_index FROM user_registration_reservations WHERE id = ?",
                reservation.getId());
        assertThat(raw.toString()).doesNotContain(key, "2000-03-03");
        assertThat(reservations.findByEmail(email.toUpperCase()))
                .map(UserRegistrationReservation::getId)
                .contains(reservation.getId());

        UUID legacy = UUID.randomUUID();
        String legacyEmail = "Old." + key + "@example.test";
        jdbc.update(
                "INSERT INTO user_registration_reservations (id, flow_id, username, email, date_of_birth, expires_at)"
                        + " VALUES (?, ?, ?, ?, '2001-01-01', now() + interval '1 hour')",
                legacy,
                UUID.randomUUID(),
                "old-" + key,
                legacyEmail);
        assertThat(reservations.findByEmail(legacyEmail.toLowerCase())).isPresent();
        backfill.backfillAccounts("user_registration_reservations");
        assertThat(jdbc.queryForObject(
                        "SELECT email LIKE 'enc:%' AND date_of_birth LIKE 'enc:%' AND email_index IS NOT NULL"
                                + " FROM user_registration_reservations WHERE id = ?",
                        Boolean.class, legacy))
                .isTrue();
        var loaded = reservations.findByEmail(legacyEmail).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(legacy);
        assertThat(loaded.getDateOfBirth()).isEqualTo(LocalDate.of(2001, 1, 1));
    }

    @Test
    void staffSearchMatchesExactEmailThroughTheIndexButNotEmailSubstrings() {
        String key = unique();
        User saved = userService.create("searchable-" + key, "Find." + key + "@example.test", LocalDate.of(1990, 1, 1));

        assertThat(userService
                        .searchActive("find." + key + "@EXAMPLE.test", null, 0, 10)
                        .getContent())
                .extracting(User::getId)
                .containsExactly(saved.getId());
        assertThat(userService
                        .searchActive(String.valueOf(saved.getId()), null, 0, 10)
                        .getContent())
                .extracting(User::getId)
                .contains(saved.getId());
        assertThat(userService.searchActive("searchable-" + key, null, 0, 10).getContent())
                .extracting(User::getId)
                .containsExactly(saved.getId());
        assertThat(userService.searchActive("find." + key, null, 0, 10).getContent())
                .isEmpty();
    }
}
