package ee.bytecore.backend.integration.payment;

/**
 * Kafka topic names for the backend <-> Payment Service integration. Single
 * source of truth shared by the outbox publisher (producer side) and the
 * result listener (consumer side) so the literal strings only appear once.
 */
public final class PaymentTopics {

    public static final String PAYMENT_REQUESTED = "payment.requested";
    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String PAYMENT_FAILED = "payment.failed";

    private PaymentTopics() {}
}
