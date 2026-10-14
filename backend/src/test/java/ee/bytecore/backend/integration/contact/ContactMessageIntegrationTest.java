package ee.bytecore.backend.integration.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.integration.payment.PaymentOutboxPublisher;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.bootstrap-servers=127.0.0.1:1",
            "spring.kafka.listener.auto-startup=false",
            "spring.docker.compose.enabled=false",
            "cart.guest.allowed-origins=http://localhost:3000",
            "support.mail-to=support@example.test",
            "contact.mail-poll-ms=3600000",
            "order.mail-poll-ms=3600000",
            "management.health.mail.enabled=false"
        })
@Import(PostgresTestConfiguration.class)
@Tag("integration")
class ContactMessageIntegrationTest {
    static final String MUTATION =
            """
            mutation($input: ContactMessageInput!) {
              submitContactMessage(input: $input) { accepted reference fieldErrors { field message } }
            }
            """;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    ContactNotifications notifications;

    @MockitoBean
    JavaMailSender mail;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PaymentOutboxPublisher outboxPublisher;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM contact_messages");
        reset(mail);
        when(mail.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
    }

    JsonNode submit(Map<String, Object> input) throws Exception {
        String body = mapper.writeValueAsString(Map.of("query", MUTATION, "variables", Map.of("input", input)));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:3000")
                .header("X-Guest-Cart-Request", "1")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = mapper.readTree(response.body());
        assertThat(json.path("errors").isMissingNode() || json.path("errors").isEmpty())
                .as(response.body())
                .isTrue();
        return json.at("/data/submitContactMessage");
    }

    static Map<String, Object> valid() {
        return Map.of(
                "name", "Mari Tamm",
                "email", "mari@example.test",
                "subject", "ORDER",
                "message", "Where is my order? It has been a week.");
    }

    @Test
    void shouldStoreMessageAndDeliverSupportAndAcknowledgementEmailsTest() throws Exception {
        JsonNode result = submit(valid());

        assertThat(result.path("accepted").asBoolean()).isTrue();
        String reference = result.path("reference").asString();
        assertThat(reference).matches("C-[0-9A-F]{8}");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM contact_messages WHERE email='mari@example.test' AND subject='ORDER'",
                        Integer.class))
                .isEqualTo(1);

        notifications.deliver();

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail, atLeast(2)).send(sent.capture());
        MimeMessage support = byRecipient(sent.getAllValues(), "support@example.test");
        MimeMessage ack = byRecipient(sent.getAllValues(), "mari@example.test");
        assertThat(((InternetAddress) support.getReplyTo()[0]).getAddress()).isEqualTo("mari@example.test");
        assertThat(support.getSubject()).contains(reference);
        assertThat((String) support.getContent()).contains("Where is my order?");
        assertThat(ack.getSubject()).contains(reference);
        assertThat((String) ack.getContent()).doesNotContain("Where is my order?");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contact_notifications WHERE status='SENT'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void shouldReturnFieldErrorsAndStoreNothingForInvalidInputTest() throws Exception {
        JsonNode result = submit(Map.of("name", " ", "email", "not-an-email", "subject", "OTHER", "message", "short"));

        assertThat(result.path("accepted").asBoolean()).isFalse();
        assertThat(result.path("reference").isNull()).isTrue();
        List<String> fields = result.path("fieldErrors")
                .valueStream()
                .map(error -> error.path("field").asString())
                .toList();
        assertThat(fields).containsExactlyInAnyOrder("name", "email", "message");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contact_messages", Integer.class))
                .isZero();
    }

    @Test
    void shouldSilentlyDropHoneypotSubmissionsTest() throws Exception {
        var input = new java.util.HashMap<>(valid());
        input.put("website", "http://spam.example");

        JsonNode result = submit(input);

        assertThat(result.path("accepted").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contact_messages", Integer.class))
                .isZero();
        notifications.deliver();
        verify(mail, never()).send(any(MimeMessage.class));
    }

    @Test
    void shouldRetryFailedDeliveryWithBackoffTest() throws Exception {
        submit(valid());
        doThrow(new MailSendException("smtp down")).when(mail).send(any(MimeMessage.class));

        notifications.deliver();

        assertThat(jdbc.queryForList(
                        "SELECT status FROM contact_notifications WHERE attempts=1 AND next_attempt_at > now()",
                        String.class))
                .containsOnly("PENDING")
                .hasSize(2);
    }

    private static MimeMessage byRecipient(List<MimeMessage> messages, String address) {
        return messages.stream()
                .filter(message -> {
                    try {
                        return ((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0])
                                .getAddress()
                                .equals(address);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("No email to " + address + " " + UUID.randomUUID()));
    }
}
