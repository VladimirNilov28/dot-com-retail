package ee.bytecore.backend.security.crypto;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import ee.bytecore.backend.services.CheckoutValues;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Idempotent startup backfill: encrypts legacy plaintext personal data and re-seals values written
 * with a retired key. Runs in small batches; safe to interrupt and re-run.
 */
@Component
public class EncryptionBackfill implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(EncryptionBackfill.class);
    private static final int BATCH = 200;
    static final List<String> ADDRESS_COLUMNS = List.of(
            "first_name", "last_name", "city", "country", "postal_code", "address_line1", "address_line2", "mobile");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FieldEncryption encryption;
    private final boolean enabled;

    public EncryptionBackfill(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            FieldEncryption encryption,
            @Value("${app.data-encryption.backfill:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.encryption = encryption;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        int addresses = backfillAddresses();
        int orders = backfillOrders();
        int notifications = backfillColumns("order_notifications", List.of("recipient", "body"));
        int contacts = backfillColumns("contact_messages", List.of("name", "email", "message"))
                + backfillColumns("contact_notifications", List.of("recipient", "reply_to", "body"));
        int accounts = backfillAccounts("users") + backfillAccounts("user_registration_reservations");
        if (addresses + orders + notifications + contacts + accounts > 0) {
            log.info(
                    "Encryption backfill sealed {} addresses, {} order snapshots, {} notifications, {} contact messages,"
                            + " {} accounts",
                    addresses,
                    orders,
                    notifications,
                    contacts,
                    accounts);
        }
    }

    int backfillAddresses() {
        return backfillColumns("user_address", ADDRESS_COLUMNS);
    }

    int backfillColumns(String table, List<String> columns) {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table);
        if (!Boolean.TRUE.equals(exists)) return 0;
        FieldCipher cipher = encryption.cipher();
        String active = FieldCipher.PREFIX + cipher.activeKeyId() + ":%";
        String pending = String.join(
                " OR ",
                columns.stream()
                        .map(c -> "(" + c + " IS NOT NULL AND " + c + " NOT LIKE '" + active.replace("'", "''") + "')")
                        .toList());
        int total = 0;
        String after = "";
        while (true) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, id::text AS cursor, " + String.join(", ", columns) + " FROM " + table
                            + " WHERE id::text > ? AND (" + pending + ") ORDER BY id::text LIMIT " + BATCH,
                    after);
            if (rows.isEmpty()) return total;
            for (Map<String, Object> row : rows) {
                Object id = row.get("id");
                after = (String) row.get("cursor");
                Object[] params = new Object[columns.size() + 1];
                for (int i = 0; i < columns.size(); i++) {
                    String value = (String) row.get(columns.get(i));
                    params[i] = value == null || !cipher.needsReencryption(value)
                            ? value
                            : cipher.encrypt(cipher.decrypt(value));
                }
                params[columns.size()] = id;
                String set =
                        String.join(", ", columns.stream().map(c -> c + " = ?").toList());
                tx.executeWithoutResult(s -> jdbc.update("UPDATE " + table + " SET " + set + " WHERE id = ?", params));
                total++;
            }
        }
    }

    /**
     * Encrypts account email/date of birth and fills {@code email_index}. Each row is rewritten only after its
     * ciphertext decrypts back to the original value, and only if the row is unchanged since it was read
     * (compare-and-set), in its own transaction. A row whose normalized email collides with another account
     * is left as readable plaintext and logged by id for manual resolution; the run continues.
     */
    int backfillAccounts(String table) {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table);
        if (!Boolean.TRUE.equals(exists)) return 0;
        FieldCipher cipher = encryption.cipher();
        String active = FieldCipher.PREFIX + cipher.activeKeyId() + ":%";
        int total = 0;
        String after = "";
        while (true) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, id::text AS cursor, email, date_of_birth, email_index FROM " + table
                            + " WHERE id::text > ? AND (email_index IS NULL OR email_index NOT LIKE ?"
                            + " OR email NOT LIKE ? OR date_of_birth NOT LIKE ?)"
                            + " ORDER BY id::text LIMIT " + BATCH,
                    after,
                    FieldCipher.USER_EMAIL_INDEX_VERSION + ":%",
                    active,
                    active);
            if (rows.isEmpty()) return total;
            for (Map<String, Object> row : rows) {
                Object id = row.get("id");
                after = (String) row.get("cursor");
                String storedEmail = (String) row.get("email");
                String storedBirth = (String) row.get("date_of_birth");
                try {
                    String email = cipher.decrypt(storedEmail);
                    String birth = cipher.decrypt(storedBirth);
                    String sealedEmail = cipher.needsReencryption(storedEmail) ? cipher.encrypt(email) : storedEmail;
                    String sealedBirth = cipher.needsReencryption(storedBirth) ? cipher.encrypt(birth) : storedBirth;
                    if (!email.equals(cipher.decrypt(sealedEmail)) || !birth.equals(cipher.decrypt(sealedBirth))) {
                        throw new IllegalStateException("ciphertext round-trip mismatch");
                    }
                    String index = cipher.userEmailIndex(email);
                    Integer updated = tx.execute(s -> jdbc.update(
                            "UPDATE " + table + " SET email = ?, date_of_birth = ?, email_index = ?"
                                    + " WHERE id = ? AND email = ? AND date_of_birth = ?",
                            sealedEmail,
                            sealedBirth,
                            index,
                            id,
                            storedEmail,
                            storedBirth));
                    if (updated != null && updated == 1) total++;
                } catch (RuntimeException e) {
                    log.warn(
                            "Encryption backfill left {} row {} unchanged: {}",
                            table,
                            id,
                            e.getClass().getSimpleName());
                }
            }
        }
    }

    int backfillOrders() {
        FieldCipher cipher = encryption.cipher();
        String active = FieldCipher.PREFIX + cipher.activeKeyId() + ":%";
        int total = 0;
        long after = 0;
        while (true) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, checkout_snapshot::text AS snapshot FROM orders WHERE id > ? AND checkout_snapshot IS NOT NULL"
                            + " AND (NOT jsonb_exists(checkout_snapshot, 'sealed') OR checkout_snapshot ->> 'sealed' NOT LIKE ?)"
                            + " ORDER BY id LIMIT " + BATCH,
                    after,
                    active);
            if (rows.isEmpty()) return total;
            for (Map<String, Object> row : rows) {
                long id = ((Number) row.get("id")).longValue();
                after = id;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> stored =
                            SealedSnapshotConverter.JSON.readValue((String) row.get("snapshot"), Map.class);
                    CheckoutValues.Snapshot snapshot =
                            stored.get(SealedSnapshotConverter.SEALED) instanceof String sealed
                                    ? SealedSnapshotConverter.JSON.readValue(
                                            cipher.decrypt(sealed), CheckoutValues.Snapshot.class)
                                    : SealedSnapshotConverter.JSON.convertValue(stored, CheckoutValues.Snapshot.class);
                    String json = SealedSnapshotConverter.JSON.writeValueAsString(
                            SealedSnapshotConverter.seal(snapshot, cipher));
                    tx.executeWithoutResult(s -> jdbc.update(
                            "UPDATE orders SET checkout_snapshot = CAST(? AS jsonb) WHERE id = ?", json, id));
                    total++;
                } catch (Exception e) {
                    log.error("Encryption backfill could not seal order {}", id, e);
                }
            }
        }
    }
}
