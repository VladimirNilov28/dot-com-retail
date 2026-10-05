package ee.bytecore.backend.integration.payment;

import ee.bytecore.backend.integration.payment.event.PaymentRequestedEvent;
import ee.bytecore.backend.integration.payment.event.RefundRequestedEvent;

/**
 * Application-level boundary between the monolith and the future Payment Service.
 * OrderService should depend only on this abstraction if/when payment initiation
 * becomes necessary; a Kafka-backed adapter is expected to implement it later.
 */
public interface PaymentEventPublisher {

    void publishPaymentRequested(PaymentRequestedEvent event);

    void publishRefundRequested(RefundRequestedEvent event);
}
