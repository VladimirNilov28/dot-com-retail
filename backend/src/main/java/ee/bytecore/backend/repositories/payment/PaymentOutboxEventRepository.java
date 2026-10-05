package ee.bytecore.backend.repositories.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import ee.bytecore.backend.entities.payment.PaymentOutboxEvent;

public interface PaymentOutboxEventRepository extends JpaRepository<PaymentOutboxEvent, UUID> {
    List<PaymentOutboxEvent> findAllByPublishedFalseOrderByCreatedAtAsc();

    @Query(
            value =
                    """
            SELECT * FROM payment_outbox
            WHERE event_type = 'payment.requested'
              AND payload ->> 'eventId' = CAST(:requestEventId AS text)
            FOR UPDATE
            """,
            nativeQuery = true)
    Optional<PaymentOutboxEvent> findRequestForUpdate(UUID requestEventId);

    interface ResultReceipt {
        UUID getPaymentId();

        Long getOrderId();

        String getResultStatus();
    }

    @Query(
            value =
                    """
            SELECT payment_id AS "paymentId", order_id AS "orderId", result_status AS "resultStatus"
            FROM payment_result_receipts WHERE request_event_id = :requestEventId
            """,
            nativeQuery = true)
    Optional<ResultReceipt> findResultByRequestEventId(UUID requestEventId);

    @Modifying
    @Query(
            value =
                    """
            INSERT INTO payment_result_receipts
                (request_event_id, outbox_event_id, order_id, payment_id, result_event_id, result_status)
            VALUES (:requestEventId, :outboxEventId, :orderId, :paymentId, :resultEventId, :resultStatus)
            ON CONFLICT DO NOTHING
            """,
            nativeQuery = true)
    int recordResult(
            UUID requestEventId,
            UUID outboxEventId,
            Long orderId,
            UUID paymentId,
            UUID resultEventId,
            String resultStatus);
}
