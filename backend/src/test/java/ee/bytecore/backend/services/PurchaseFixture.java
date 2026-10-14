package ee.bytecore.backend.services;

import java.util.Collection;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** Writes minimal order rows so review tests can satisfy (or deliberately miss) the purchase rule. */
public final class PurchaseFixture {
    private PurchaseFixture() {}

    /** A paid order of the product, which qualifies the user to review it. */
    public static long paid(JdbcTemplate jdbc, long userId, long productId) {
        return order(jdbc, userId, productId, "PAID", "SUCCEEDED");
    }

    public static long order(JdbcTemplate jdbc, long userId, long productId, String status, String paymentStatus) {
        Long variant = jdbc.query(
                "SELECT id FROM product_variants WHERE product_id = ? ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null,
                productId);
        if (variant == null) {
            variant = jdbc.queryForObject(
                    "INSERT INTO product_variants(product_id, sku, price) VALUES (?, ?, 10) RETURNING id",
                    Long.class,
                    productId,
                    "purchase-" + UUID.randomUUID());
        }
        String warehouseName = warehouse(userId);
        Long warehouse = jdbc.query(
                "SELECT id FROM warehouses WHERE name = ?", rs -> rs.next() ? rs.getLong(1) : null, warehouseName);
        if (warehouse == null) {
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouses(name) VALUES (?) RETURNING id", Long.class, warehouseName);
        }
        Long inventory = jdbc.queryForObject(
                "INSERT INTO inventory(product_variant_id, warehouse_id, quantity) VALUES (?, ?, 0)"
                        + " ON CONFLICT (product_variant_id, warehouse_id) DO UPDATE SET quantity = 0 RETURNING id",
                Long.class,
                variant,
                warehouse);
        Long order = jdbc.queryForObject(
                "INSERT INTO orders(user_id, status, total_amount, payment_status)"
                        + " VALUES (?, CAST(? AS order_status), 10, ?) RETURNING id",
                Long.class,
                userId,
                status,
                paymentStatus);
        jdbc.update(
                "INSERT INTO order_items(order_id, product_variant_id, inventory_id, quantity, price_at_purchase)"
                        + " VALUES (?, ?, ?, 1, 10)",
                order,
                variant,
                inventory);
        return order;
    }

    /** Records a full refund of the order's payment with the given outcome. */
    public static void refund(JdbcTemplate jdbc, long orderId, String status) {
        UUID outbox = jdbc.queryForObject(
                "INSERT INTO payment_outbox(order_id, event_type, payload) VALUES (?, 'payment.requested', '{}')"
                        + " RETURNING id",
                UUID.class,
                orderId);
        UUID requestEvent = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO payment_result_receipts(request_event_id, outbox_event_id, order_id, payment_id,"
                        + " result_event_id, result_status) VALUES (?, ?, ?, ?, ?, 'PAID')",
                requestEvent,
                outbox,
                orderId,
                payment,
                UUID.randomUUID());
        jdbc.update(
                "INSERT INTO order_refunds(id, request_event_id, payment_request_event_id, order_id, payment_id,"
                        + " amount, currency, status) VALUES (?, ?, ?, ?, ?, 10, 'EUR', ?)",
                UUID.randomUUID(),
                UUID.randomUUID(),
                requestEvent,
                orderId,
                payment,
                status);
    }

    public static void rejectedRefundRequest(JdbcTemplate jdbc, long orderId) {
        jdbc.update(
                "INSERT INTO refund_requests(id, order_id, requested_by, reason, status, decided_by, decided_at)"
                        + " VALUES (?, ?, 'customer', 'Changed my mind', 'REJECTED', 'staff', NOW())",
                UUID.randomUUID(),
                orderId);
    }

    /** Removes every order fixture of the users; call before deleting their products and accounts. */
    public static void cleanup(JdbcTemplate jdbc, Collection<Long> userIds) {
        for (Long userId : userIds) {
            String orders = "SELECT id FROM orders WHERE user_id = " + userId;
            jdbc.update("DELETE FROM refund_requests WHERE order_id IN (" + orders + ")");
            jdbc.update("DELETE FROM order_refunds WHERE order_id IN (" + orders + ")");
            jdbc.update("DELETE FROM payment_result_receipts WHERE order_id IN (" + orders + ")");
            jdbc.update("DELETE FROM payment_outbox WHERE order_id IN (" + orders + ")");
            jdbc.update("DELETE FROM orders WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM warehouses WHERE name = ?", warehouse(userId));
        }
    }

    private static String warehouse(long userId) {
        return "purchase-fixture-" + userId;
    }
}
