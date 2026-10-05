package ee.bytecore.backend.migration;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@Tag("integration")
class OrderRecoveryMigrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void upgradePreservesSnapshotsAndCreatesRefundOnlyForProvenCharge() {
        var config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        config.target(MigrationVersion.fromVersion("13")).load().migrate();
        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        Long user = jdbc.queryForObject(
                "INSERT INTO users(username,email,date_of_birth) VALUES ('migration','migration@example.com','1990-01-01') RETURNING id",
                Long.class);
        Long charged = jdbc.queryForObject(
                "INSERT INTO orders(user_id,status,total_amount) VALUES (?,'CANCELLED',44.97) RETURNING id",
                Long.class,
                user);
        Long unknown = jdbc.queryForObject(
                "INSERT INTO orders(user_id,status,total_amount) VALUES (?,'CANCELLED',44.97) RETURNING id",
                Long.class,
                user);
        UUID request = UUID.randomUUID(), payment = UUID.randomUUID();
        UUID outbox = jdbc.queryForObject(
                "INSERT INTO payment_outbox(order_id,event_type,payload) VALUES (?,'payment.requested',"
                        + "jsonb_build_object('eventId',?::text,'orderId',?::bigint,'amount',44.97,'currency','EUR')) RETURNING id",
                UUID.class,
                charged,
                request.toString(),
                charged);
        jdbc.update(
                "INSERT INTO payment_result_receipts(request_event_id,outbox_event_id,order_id,payment_id,result_event_id,result_status)"
                        + " VALUES (?,?,?,?,?,'PAID')",
                request,
                outbox,
                charged,
                payment,
                UUID.randomUUID());
        config.target(MigrationVersion.LATEST).load().migrate();
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class, charged))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class, unknown))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM orders WHERE inventory_released_at IS NOT NULL", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_refunds", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT amount FROM order_refunds WHERE payment_id=?", java.math.BigDecimal.class, payment))
                .isEqualByComparingTo("44.97");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment_outbox WHERE event_type='refund.requested'", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE order_refunds SET amount=1 WHERE payment_id=?", payment))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET inventory_released_at=NULL WHERE id=?", charged))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET status='PAID' WHERE id=?", charged))
                .hasMessageContaining("resurrected");
        config.load().migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_refunds", Integer.class))
                .isEqualTo(1);
    }
}
