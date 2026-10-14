package ee.bytecore.backend.services;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import ee.bytecore.backend.entities.payment.Order;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@Tag("unit")
class OrderStatusPublisherTest {

    @Test
    void shouldDeliverAfterLastSubscriberDisconnectsTest() {
        OrderStatusPublisher publisher = new OrderStatusPublisher();
        Order order = order(1L);
        StepVerifier.create(publisher.subscribeTo(1L).take(1))
                .then(() -> publisher.publish(order))
                .expectNext(order)
                .verifyComplete();
        StepVerifier.create(publisher.subscribeTo(1L))
                .then(() -> publisher.publish(order))
                .expectNext(order)
                .thenCancel()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void shouldNotReplayUpdatesPublishedWithoutSubscribersTest() {
        OrderStatusPublisher publisher = new OrderStatusPublisher();
        Order order = order(1L);
        publisher.publish(order);
        StepVerifier.create(publisher.subscribeTo(1L))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(50))
                .then(() -> publisher.publish(order))
                .expectNext(order)
                .thenCancel()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void shouldIsolateOrderStreamsTest() {
        OrderStatusPublisher publisher = new OrderStatusPublisher();
        Order requested = order(1L);
        StepVerifier.create(publisher.subscribeTo(1L))
                .then(() -> publisher.publish(order(2L)))
                .then(() -> publisher.publish(requested))
                .expectNext(requested)
                .thenCancel()
                .verify(Duration.ofSeconds(2));
    }

    private Order order(Long id) {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(id);
        return order;
    }
}
