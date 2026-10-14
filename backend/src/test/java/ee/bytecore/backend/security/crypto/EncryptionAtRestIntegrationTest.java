package ee.bytecore.backend.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.entities.user.UserAddress;
import ee.bytecore.backend.repositories.user.UserAddressRepository;
import ee.bytecore.backend.repositories.user.UserRepository;
import ee.bytecore.backend.services.CheckoutValues;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Personal data is stored as AES-GCM ciphertext; legacy plaintext is backfilled; snapshots stay immutable. */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false",
        "spring.docker.compose.enabled=false", "order.mail-poll-ms=999999999", "management.health.mail.enabled=false"})
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class EncryptionAtRestIntegrationTest {
    @Autowired UserRepository users;
    @Autowired UserAddressRepository addresses;
    @Autowired JdbcTemplate jdbc;
    @Autowired EncryptionBackfill backfill;
    @Autowired FieldEncryption encryption;

    private User user() {
        String key = UUID.randomUUID().toString();
        return users.save(User.create(key, key + "@example.test", LocalDate.of(1990, 1, 1)));
    }

    @Test
    void addressColumnsAreCiphertextAndReadBackAsPlaintext() {
        var saved = addresses.save(UserAddress.create(
                user(), "Ada", "Lovelace", "Tallinn", "EE", "10111", "Narva mnt 5", null, "+3725550000"));
        var raw = jdbc.queryForMap("SELECT first_name, address_line1, mobile FROM user_address WHERE id=?", saved.getId());
        assertThat(raw.values()).allSatisfy(v -> assertThat((String) v).startsWith("enc:"));
        assertThat(raw.toString()).doesNotContain("Ada", "Narva", "5550000");
        var loaded = addresses.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getFirstName()).isEqualTo("Ada");
        assertThat(loaded.getAddressLine1()).isEqualTo("Narva mnt 5");
    }

    @Test
    void backfillEncryptsLegacyPlaintextAddresses() {
        var u = user();
        Long id = jdbc.queryForObject(
                "INSERT INTO user_address(user_id, first_name, city) VALUES (?, 'Legacy', 'Tartu') RETURNING id",
                Long.class, u.getId());
        backfill.backfillAddresses();
        String stored = jdbc.queryForObject("SELECT city FROM user_address WHERE id=?", String.class, id);
        assertThat(stored).startsWith("enc:");
        assertThat(addresses.findById(id).orElseThrow().getCity()).isEqualTo("Tartu");
    }

    @Test
    void legacyOrderSnapshotIsSealedOnceAndThenImmutable() throws Exception {
        var u = user();
        var address = new CheckoutValues.Address("Ada", "L", "Tallinn", "EE", "10111", "Narva mnt 5", null, null);
        var snapshot = new CheckoutValues.Snapshot(1, UUID.randomUUID(),
                List.of(new CheckoutValues.Line(1L, "Thing", "SKU", java.util.Map.of(), 1, new BigDecimal("9.99"), new BigDecimal("9.99"))),
                new CheckoutValues.Details("legacy@example.test", null, address, address,
                        new CheckoutValues.ShippingOption("STANDARD", new BigDecimal("4.99"), "EUR", "3-5 days", List.of("EE"), null),
                        "STRIPE", null),
                new CheckoutValues.Totals(new BigDecimal("9.99"), new BigDecimal("4.99"), new BigDecimal("14.98"), "EUR"));
        String legacy = SealedSnapshotConverter.JSON.writeValueAsString(snapshot);
        Long id = jdbc.queryForObject(
                "INSERT INTO orders(public_id, user_id, status, total_amount, checkout_snapshot) "
                        + "VALUES (?, ?, 'PENDING', 14.98, CAST(? AS jsonb)) RETURNING id",
                Long.class, UUID.randomUUID(), u.getId(), legacy);

        backfill.backfillOrders();

        String stored = jdbc.queryForObject("SELECT checkout_snapshot::text FROM orders WHERE id=?", String.class, id);
        assertThat(stored).contains("\"sealed\"", "contactEmailIndex").doesNotContain("Narva", "legacy@example.test");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE checkout_snapshot ->> 'contactEmailIndex' = ?", Integer.class,
                FieldEncryption.contactEmailIndex(" Legacy@Example.test "))).isEqualTo(1);
        var converted = new SealedSnapshotConverter().convertToEntityAttribute(
                SealedSnapshotConverter.JSON.readValue(stored, java.util.Map.class));
        assertThat(converted.details().shippingAddress().addressLine1()).isEqualTo("Narva mnt 5");
        assertThat(converted.totals().total()).isEqualByComparingTo("14.98");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE orders SET checkout_snapshot = CAST(? AS jsonb) WHERE id=?", legacy, id))
                .hasMessageContaining("immutable");
    }
}
