package ee.bytecore.backend.integration.payment;

import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ee.bytecore.backend.entities.payment.Order;

import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class OrderNotifications {
    private static final Logger LOG = LoggerFactory.getLogger(OrderNotifications.class);
    private final JdbcTemplate jdbc;
    private final JavaMailSender mail;
    private final TransactionTemplate transaction;
    private final String sender;

    public OrderNotifications(
            JdbcTemplate jdbc,
            JavaMailSender mail,
            PlatformTransactionManager transactions,
            @Value("${order.mail-from:no-reply@bytecore.ee}") String sender) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.transaction = new TransactionTemplate(transactions);
        this.sender = sender;
    }

    public void enqueue(Order order, String kind) {
        var snapshot = order.getCheckoutSnapshot();
        if (snapshot == null) return;
        String description =
                switch (kind) {
                    case "PLACED" -> "Your order is placed. Payment is pending; this is not a payment receipt.";
                    case "PAID" -> "Your payment has been confirmed.";
                    case "PAYMENT_RETRY_REQUIRED" -> "Payment did not complete. Return to the same order in your browser to retry.";
                    case "CANCELLED" -> "Your order has been cancelled. Any pending payment/refund is handled separately.";
                    default -> throw new IllegalArgumentException("Unsupported order notification");
                };
        StringBuilder body = new StringBuilder(description)
                .append("\n\nOrder reference: ")
                .append(order.getPublicId())
                .append("\nOrder state: ")
                .append(order.getStatus())
                .append("\nPayment state: ")
                .append(order.getPaymentStatus())
                .append("\n\n");
        for (var line : snapshot.lines()) {
            body.append(line.productName())
                    .append(" (")
                    .append(line.sku())
                    .append(") x")
                    .append(line.quantity())
                    .append(": ")
                    .append(line.subtotal().toPlainString())
                    .append(" ")
                    .append(snapshot.totals().currency())
                    .append("\n");
        }
        body.append("\nShipping: ")
                .append(snapshot.details().shipping().method())
                .append(" / ")
                .append(snapshot.totals().shippingCharge().toPlainString())
                .append("\nTotal: ")
                .append(snapshot.totals().total().toPlainString())
                .append(" ")
                .append(snapshot.totals().currency())
                .append("\n\nOpen your original confirmation page to see the current state. ")
                .append("This email does not grant access to the order.");
        jdbc.update(
                """
                INSERT INTO order_notifications(id,order_id,kind,recipient,subject,body)
                VALUES (?,?,?,?,?,?) ON CONFLICT(order_id,kind) DO NOTHING
                """,
                UUID.randomUUID(),
                order.getId(),
                kind,
                snapshot.details().contactEmail(),
                "ByteCore order " + order.getPublicId() + " — " + kind.replace('_', ' '),
                body.toString());
    }

    public record Pending(UUID id, String recipient, String subject, String body, int attempts) {}

    @Scheduled(fixedDelayString = "${order.mail-poll-ms:5000}", initialDelayString = "${order.mail-poll-ms:5000}")
    public void deliver() {
        transaction.executeWithoutResult(status -> {
            var pending = jdbc.query(
                    """
                    SELECT id,recipient,subject,body,attempts FROM order_notifications
                    WHERE status='PENDING' AND next_attempt_at<=now()
                    ORDER BY next_attempt_at LIMIT 10 FOR UPDATE SKIP LOCKED
                    """,
                    (rs, row) -> new Pending(
                            rs.getObject("id", UUID.class),
                            rs.getString("recipient"),
                            rs.getString("subject"),
                            rs.getString("body"),
                            rs.getInt("attempts")));
            for (var notification : pending) {
                try {
                    var message = mail.createMimeMessage();
                    var helper = new MimeMessageHelper(message, "UTF-8");
                    helper.setFrom(sender);
                    helper.setTo(notification.recipient());
                    helper.setSubject(notification.subject());
                    helper.setText(notification.body(), false);
                    message.setHeader("Message-ID", "<order-" + notification.id() + "@bytecore.local>");
                    mail.send(message);
                    jdbc.update(
                            "UPDATE order_notifications SET status='SENT',sent_at=now(),attempts=attempts+1 WHERE id=?",
                            notification.id());
                } catch (MailException | MessagingException exception) {
                    int attempts = notification.attempts() + 1;
                    jdbc.update(
                            """
                            UPDATE order_notifications SET attempts=?,status=?,next_attempt_at=? WHERE id=?
                            """,
                            attempts,
                            attempts >= 8 ? "DEAD" : "PENDING",
                            java.sql.Timestamp.from(Instant.now().plusSeconds(Math.min(3600, 30L << attempts))),
                            notification.id());
                    LOG.warn(
                            "Order notification delivery failed id={} attempt={} type={}",
                            notification.id(),
                            attempts,
                            exception.getClass().getSimpleName());
                }
            }
        });
    }
}
