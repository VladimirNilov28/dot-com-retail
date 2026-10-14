package ee.bytecore.backend.integration.contact;

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

import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retryable email outbox for contact-form submissions, mirroring
 * {@code OrderNotifications}: rows are enqueued in the submitting transaction
 * and delivered by a poller (PENDING -> SENT, DEAD after 8 attempts).
 */
@Component
public class ContactNotifications {
    private static final Logger LOG = LoggerFactory.getLogger(ContactNotifications.class);
    private static final int MAX_ATTEMPTS = 8;

    private final JdbcTemplate jdbc;
    private final JavaMailSender mail;
    private final TransactionTemplate transaction;
    private final String sender;

    public ContactNotifications(
            JdbcTemplate jdbc,
            JavaMailSender mail,
            PlatformTransactionManager transactions,
            @Value("${order.mail-from:no-reply@bytecore.ee}") String sender) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.transaction = new TransactionTemplate(transactions);
        this.sender = sender;
    }

    public void enqueue(UUID messageId, String kind, String recipient, String replyTo, String subject, String body) {
        jdbc.update(
                """
                INSERT INTO contact_notifications(id,message_id,kind,recipient,reply_to,subject,body)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT(message_id,kind) DO NOTHING
                """,
                UUID.randomUUID(),
                messageId,
                kind,
                recipient,
                replyTo,
                subject,
                body);
    }

    record Pending(UUID id, String recipient, String replyTo, String subject, String body, int attempts) {}

    @Scheduled(fixedDelayString = "${contact.mail-poll-ms:5000}", initialDelayString = "${contact.mail-poll-ms:5000}")
    public void deliver() {
        transaction.executeWithoutResult(status -> {
            var pending = jdbc.query(
                    """
                    SELECT id,recipient,reply_to,subject,body,attempts FROM contact_notifications
                    WHERE status='PENDING' AND next_attempt_at<=now()
                    ORDER BY next_attempt_at LIMIT 10 FOR UPDATE SKIP LOCKED
                    """,
                    (rs, row) -> new Pending(
                            rs.getObject("id", UUID.class),
                            rs.getString("recipient"),
                            rs.getString("reply_to"),
                            rs.getString("subject"),
                            rs.getString("body"),
                            rs.getInt("attempts")));
            for (var notification : pending) {
                try {
                    var message = mail.createMimeMessage();
                    var helper = new MimeMessageHelper(message, "UTF-8");
                    helper.setFrom(sender);
                    helper.setTo(notification.recipient());
                    if (notification.replyTo() != null) helper.setReplyTo(notification.replyTo());
                    helper.setSubject(notification.subject());
                    helper.setText(notification.body(), false);
                    message.setHeader("Message-ID", "<contact-" + notification.id() + "@bytecore.local>");
                    mail.send(message);
                    jdbc.update(
                            "UPDATE contact_notifications SET status='SENT',sent_at=now(),attempts=attempts+1 WHERE id=?",
                            notification.id());
                } catch (MailException | MessagingException exception) {
                    int attempts = notification.attempts() + 1;
                    jdbc.update(
                            "UPDATE contact_notifications SET attempts=?,status=?,next_attempt_at=? WHERE id=?",
                            attempts,
                            attempts >= MAX_ATTEMPTS ? "DEAD" : "PENDING",
                            java.sql.Timestamp.from(Instant.now().plusSeconds(Math.min(3600, 30L << attempts))),
                            notification.id());
                    LOG.warn(
                            "Contact notification delivery failed id={} attempt={} type={}",
                            notification.id(),
                            attempts,
                            exception.getClass().getSimpleName());
                }
            }
        });
    }
}
