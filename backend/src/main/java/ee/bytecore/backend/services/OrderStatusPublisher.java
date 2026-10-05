package ee.bytecore.backend.services;

import org.springframework.stereotype.Component;

import ee.bytecore.backend.entities.payment.Order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Publishes Order status changes for the {@code orderStatusChanged} GraphQL
 * subscription. Multicast, non-replaying: a new subscriber only sees updates
 * that happen after it subscribes (a replaying sink would leak an unrelated
 * order's last-known status to a brand-new subscriber).
 */
@Component
public class OrderStatusPublisher {

    private static final Logger logger = LoggerFactory.getLogger(OrderStatusPublisher.class);

    private final Sinks.Many<Order> sink = Sinks.many().multicast().directBestEffort();

    public synchronized void publish(Order order) {
        Sinks.EmitResult result = sink.tryEmitNext(order);
        if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            logger.warn("Unable to deliver order status notification for order {}: {}", order.getId(), result);
        }
    }

    public Flux<Order> subscribeTo(Long orderId) {
        return sink.asFlux()
                .filter(order -> order.getId() != null && order.getId().equals(orderId));
    }
}
