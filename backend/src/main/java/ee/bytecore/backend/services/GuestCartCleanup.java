package ee.bytecore.backend.services;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.config.GuestCartSettings;

@Component
public class GuestCartCleanup {
    private final JdbcTemplate jdbc;
    private final GuestCartSettings settings;

    public GuestCartCleanup(JdbcTemplate jdbc, GuestCartSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${cart.guest.cleanup-interval:PT1H}")
    @Transactional
    public void cleanExpired() {
        jdbc.update(
                """
                DELETE FROM carts WHERE id IN (
                  SELECT id FROM carts WHERE user_id IS NULL AND guest_expires_at <= NOW()
                  ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                """,
                settings.getCleanupBatchSize());
        jdbc.update(
                """
                DELETE FROM guest_cart_merge_receipts WHERE id IN (
                  SELECT id FROM guest_cart_merge_receipts WHERE expires_at <= NOW()
                  ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                """,
                settings.getCleanupBatchSize());
    }
}
