package ee.bytecore.backend.services;

import org.springframework.stereotype.Component;

import ee.bytecore.backend.entities.payment.Order;

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

    private final Sinks.Many<Order> sink = Sinks.many().multicast().onBackpressureBuffer();

    public void publish(Order order) {
        sink.tryEmitNext(order);
    }

    public Flux<Order> subscribeTo(Long orderId) {
        return sink.asFlux()
                .filter(order -> order.getId() != null && order.getId().equals(orderId));
    }
}
