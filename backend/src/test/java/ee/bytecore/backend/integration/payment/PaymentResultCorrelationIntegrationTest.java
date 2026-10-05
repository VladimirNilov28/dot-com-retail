package ee.bytecore.backend.integration.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ee.bytecore.backend.config.KafkaTestConfiguration;
import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.payment.PaymentOutboxEvent;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.integration.payment.event.PaymentFailedEvent;
import ee.bytecore.backend.integration.payment.event.PaymentRequestedEvent;
import ee.bytecore.backend.integration.payment.event.PaymentSucceededEvent;
import ee.bytecore.backend.repositories.payment.OrderRepository;
import ee.bytecore.backend.repositories.payment.PaymentOutboxEventRepository;
import ee.bytecore.backend.repositories.user.UserRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SpringBootTest
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
@Tag("integration")
class PaymentResultCorrelationIntegrationTest {
    @MockitoBean
    PaymentOutboxPublisher outboxPublisher;

    @Autowired
    UserRepository users;

    @Autowired
    OrderRepository orders;

    @Autowired
    PaymentOutboxEventRepository outbox;

    @Autowired
    PaymentResultListener listener;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JdbcTemplate jdbc;

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private record PendingRequest(Order order, UUID requestEventId) {}

    private PendingRequest pendingRequest() throws Exception {
        String key = UUID.randomUUID().toString();
        User user = users.save(User.create(key, key + "@example.com", LocalDate.of(1990, 1, 1)));
        Order order = orders.save(Order.create(user, OrderStatus.PENDING, BigDecimal.TEN));
        UUID requestId = UUID.randomUUID();
        PaymentRequestedEvent request =
                new PaymentRequestedEvent(requestId, order.getId(), user.getId(), BigDecimal.TEN, "EUR", Instant.now());
        outbox.save(PaymentOutboxEvent.create(
                order.getId(), PaymentTopics.PAYMENT_REQUESTED, JSON.writeValueAsString(request)));
        return new PendingRequest(order, requestId);
    }

    @Test
    void unrecognizedRequestEventIdMustNotMarkOrderPaid() throws Exception {
        PendingRequest target = pendingRequest();
        UUID unrecognizedRequestId = UUID.randomUUID();
        assertThat(unrecognizedRequestId).isNotEqualTo(target.requestEventId());
        PaymentSucceededEvent result = new PaymentSucceededEvent(
                UUID.randomUUID(), unrecognizedRequestId, target.order().getId(), UUID.randomUUID(), Instant.now());

        listener.onPaymentSucceeded(JSON.writeValueAsString(result));

        assertThat(orders.findById(target.order().getId()).orElseThrow().getStatus())
                .as("an unknown requestEventId cannot authorize payment of a pending order")
                .isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void anotherOrdersRequestEventIdMustNotMarkTargetOrderPaid() throws Exception {
        PendingRequest source = pendingRequest();
        PendingRequest target = pendingRequest();
        PaymentSucceededEvent result = new PaymentSucceededEvent(
                UUID.randomUUID(), source.requestEventId(), target.order().getId(), UUID.randomUUID(), Instant.now());

        listener.onPaymentSucceeded(JSON.writeValueAsString(result));

        assertThat(orders.findById(source.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING);
        assertThat(orders.findById(target.order().getId()).orElseThrow().getStatus())
                .as("a requestEventId issued for a different order cannot authorize payment of the target")
                .isEqualTo(OrderStatus.PENDING);
    }

    private PaymentSucceededEvent success(PendingRequest request, UUID paymentId) {
        return new PaymentSucceededEvent(
                UUID.randomUUID(), request.requestEventId(), request.order().getId(), paymentId, Instant.now());
    }

    @Test
    void correlatedSuccessAndDuplicateSettleOnlyTheirOrder() throws Exception {
        PendingRequest request = pendingRequest();
        UUID paymentId = UUID.randomUUID();
        String payload = JSON.writeValueAsString(success(request, paymentId));
        listener.onPaymentSucceeded(payload);
        listener.onPaymentSucceeded(payload);
        assertThat(orders.findById(request.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(outbox.findResultByRequestEventId(request.requestEventId())).hasValueSatisfying(receipt -> {
            assertThat(receipt.getPaymentId()).isEqualTo(paymentId);
            assertThat(receipt.getOrderId()).isEqualTo(request.order().getId());
            assertThat(receipt.getResultStatus()).isEqualTo("PAID");
        });
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment_result_receipts WHERE request_event_id = ?",
                        Long.class,
                        request.requestEventId()))
                .isEqualTo(1);
    }

    @Test
    void correlatedDeclineAndDuplicateCancelOnlyTheirOrder() throws Exception {
        PendingRequest request = pendingRequest();
        String payload = JSON.writeValueAsString(new PaymentFailedEvent(
                UUID.randomUUID(),
                request.requestEventId(),
                request.order().getId(),
                UUID.randomUUID(),
                "declined",
                Instant.now()));
        listener.onPaymentFailed(payload);
        listener.onPaymentFailed(payload);
        Order reloaded = orders.findById(request.order().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reloaded.getCancellationReason()).contains("declined");
        assertThat(outbox.findResultByRequestEventId(request.requestEventId()))
                .hasValueSatisfying(
                        receipt -> assertThat(receipt.getResultStatus()).isEqualTo("CANCELLED"));
    }

    @Test
    void paymentIdAlreadyUsedForAnotherOrderMustNotSettleTarget() throws Exception {
        PendingRequest source = pendingRequest();
        PendingRequest target = pendingRequest();
        UUID paymentId = UUID.randomUUID();
        listener.onPaymentSucceeded(JSON.writeValueAsString(success(source, paymentId)));

        listener.onPaymentSucceeded(JSON.writeValueAsString(success(target, paymentId)));

        assertThat(orders.findById(source.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(orders.findById(target.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void resultEventIdAlreadyUsedForAnotherRequestMustNotSettleTarget() throws Exception {
        PendingRequest source = pendingRequest();
        PendingRequest target = pendingRequest();
        PaymentSucceededEvent accepted = success(source, UUID.randomUUID());
        listener.onPaymentSucceeded(JSON.writeValueAsString(accepted));
        listener.onPaymentSucceeded(JSON.writeValueAsString(new PaymentSucceededEvent(
                accepted.eventId(),
                target.requestEventId(),
                target.order().getId(),
                UUID.randomUUID(),
                Instant.now())));
        assertThat(orders.findById(target.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING);
        assertThat(outbox.findResultByRequestEventId(target.requestEventId())).isEmpty();
    }

    @Test
    void unknownAndWrongOrderFailuresMustNotCancelTarget() throws Exception {
        PendingRequest source = pendingRequest();
        PendingRequest target = pendingRequest();
        for (UUID requestId : java.util.List.of(UUID.randomUUID(), source.requestEventId())) {
            listener.onPaymentFailed(JSON.writeValueAsString(new PaymentFailedEvent(
                    UUID.randomUUID(),
                    requestId,
                    target.order().getId(),
                    UUID.randomUUID(),
                    "declined",
                    Instant.now())));
            Order reloaded = orders.findById(target.order().getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(reloaded.getCancellationReason()).isNull();
        }
        assertThat(outbox.findResultByRequestEventId(target.requestEventId())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void malformedDeclineReasonMustNotCancelOrder(String reason) throws Exception {
        PendingRequest request = pendingRequest();
        String payload = JSON.writeValueAsString(new PaymentFailedEvent(
                UUID.randomUUID(),
                request.requestEventId(),
                request.order().getId(),
                UUID.randomUUID(),
                reason,
                Instant.now()));
        assertThatThrownBy(() -> listener.onPaymentFailed(payload)).isInstanceOf(IllegalStateException.class);
        assertThat(orders.findById(request.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void conflictingPaymentIdMustNotCancelPreviouslySettledRequest() throws Exception {
        PendingRequest request = pendingRequest();
        listener.onPaymentSucceeded(JSON.writeValueAsString(success(request, UUID.randomUUID())));

        listener.onPaymentFailed(JSON.writeValueAsString(new PaymentFailedEvent(
                UUID.randomUUID(),
                request.requestEventId(),
                request.order().getId(),
                UUID.randomUUID(),
                "declined",
                Instant.now())));

        assertThat(orders.findById(request.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    @Test
    void contradictoryOutcomeForSamePaymentMustNotCancelPreviouslySettledRequest() throws Exception {
        PendingRequest request = pendingRequest();
        UUID paymentId = UUID.randomUUID();
        listener.onPaymentSucceeded(JSON.writeValueAsString(success(request, paymentId)));

        listener.onPaymentFailed(JSON.writeValueAsString(new PaymentFailedEvent(
                UUID.randomUUID(),
                request.requestEventId(),
                request.order().getId(),
                paymentId,
                "declined",
                Instant.now())));

        assertThat(orders.findById(request.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "requestEventId", "paymentId", "occurredAt", "orderId", "status"})
    void malformedOrUnrecognizedResultMustNotSettleOrder(String field) throws Exception {
        PendingRequest request = pendingRequest();
        var payload = JSON.valueToTree(success(request, UUID.randomUUID()));
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) payload;
        if ("status".equals(field)) object.put(field, "UNRECOGNIZED");
        else object.remove(field);

        assertThatThrownBy(() -> listener.onPaymentSucceeded(object.toString()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(orders.findById(request.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void rolledBackResultDoesNotPreventValidRedelivery() throws Exception {
        PendingRequest request = pendingRequest();
        String payload = JSON.writeValueAsString(success(request, UUID.randomUUID()));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            listener.onPaymentSucceeded(payload);
            status.setRollbackOnly();
        });
        assertThat(orders.findById(request.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING);
        assertThat(outbox.findResultByRequestEventId(request.requestEventId())).isEmpty();
        listener.onPaymentSucceeded(payload);
        assertThat(orders.findById(request.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }
}
