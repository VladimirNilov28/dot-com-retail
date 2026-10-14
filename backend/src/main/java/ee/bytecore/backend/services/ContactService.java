package ee.bytecore.backend.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import ee.bytecore.backend.security.crypto.FieldEncryption;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.bytecore.backend.integration.contact.ContactNotifications;

/**
 * Stores contact-form submissions and enqueues the support email (Reply-To the
 * sender) plus an acknowledgement to the sender that does not echo the message.
 */
@Service
public class ContactService {
    static final int NAME_MAX = 100;
    static final int EMAIL_MAX = 254;
    static final int MESSAGE_MIN = 10;
    static final int MESSAGE_MAX = 4000;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@<>\"(),;:]+@[^\\s@<>\"(),;:]+\\.[^\\s@<>\"(),;:]+$");
    private static final Map<String, String> SUBJECTS = Map.of(
            "ORDER", "Order question",
            "PAYMENT", "Payment or refund",
            "PRODUCT", "Product question",
            "ACCOUNT", "Account and sign-in",
            "FEEDBACK", "Feedback",
            "OTHER", "Something else");

    public record Submission(String name, String email, String subject, String message, String website) {}

    public record FieldError(String field, String message) {}

    public record Result(boolean accepted, String reference, List<FieldError> fieldErrors) {
        static Result rejected(List<FieldError> errors) {
            return new Result(false, null, List.copyOf(errors));
        }
    }

    private final JdbcTemplate jdbc;
    private final ContactNotifications notifications;
    private final String supportAddress;

    public ContactService(
            JdbcTemplate jdbc, ContactNotifications notifications, @Value("${support.mail-to}") String supportAddress) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.supportAddress = supportAddress;
    }

    @Transactional
    public Result submit(Submission submission, Long userId) {
        String reference = shortReference(UUID.randomUUID());
        if (submission.website() != null && !submission.website().isBlank()) {
            // Honeypot filled: look successful, store and send nothing.
            return new Result(true, reference, List.of());
        }
        List<FieldError> errors = new ArrayList<>();
        String name = trimmed(submission.name());
        String email = trimmed(submission.email());
        String subject = submission.subject();
        String message =
                submission.message() == null ? "" : submission.message().strip();

        if (name.isEmpty()) errors.add(new FieldError("name", "Enter your name."));
        else if (name.length() > NAME_MAX) errors.add(new FieldError("name", "Name must be at most 100 characters."));
        else if (name.chars().anyMatch(Character::isISOControl))
            errors.add(new FieldError("name", "Name contains invalid characters."));

        if (email.isEmpty()) errors.add(new FieldError("email", "Enter your email address."));
        else if (email.length() > EMAIL_MAX || !EMAIL.matcher(email).matches())
            errors.add(new FieldError("email", "Enter a valid email address."));

        if (subject == null || !SUBJECTS.containsKey(subject)) errors.add(new FieldError("subject", "Choose a topic."));

        if (message.length() < MESSAGE_MIN)
            errors.add(new FieldError("message", "Message must be at least 10 characters."));
        else if (message.length() > MESSAGE_MAX)
            errors.add(new FieldError("message", "Message must be at most 4000 characters."));
        else if (message.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            errors.add(new FieldError("message", "Message contains invalid characters."));

        if (!errors.isEmpty()) return Result.rejected(errors);

        UUID id = UUID.randomUUID();
        reference = shortReference(id);
        jdbc.update(
                "INSERT INTO contact_messages(id,name,email,subject,message,user_id) VALUES (?,?,?,?,?,?)",
                id,
                FieldEncryption.seal(name),
                FieldEncryption.seal(email),
                subject,
                FieldEncryption.seal(message),
                userId);
        String topic = SUBJECTS.get(subject);
        notifications.enqueue(
                id,
                "SUPPORT",
                supportAddress,
                email,
                "[Contact " + reference + "] " + topic + " — " + name,
                "New contact-form message " + reference + "\n\nFrom: " + name + " <" + email + ">\nTopic: " + topic
                        + "\nSigned-in user id: " + (userId == null ? "none" : userId)
                        + "\n\n" + message + "\n\nReply to this email to answer the sender.");
        notifications.enqueue(
                id,
                "ACKNOWLEDGEMENT",
                email,
                null,
                "We received your message (" + reference + ")",
                "Hello " + name + ",\n\nThanks for contacting ByteCore. We received your message about \"" + topic
                        + "\" and will reply to this address.\n\nReference: " + reference
                        + "\n\nIf you did not send this message, you can ignore this email.\n\nByteCore");
        return new Result(true, reference, List.of());
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.strip();
    }

    static String shortReference(UUID id) {
        return "C-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
