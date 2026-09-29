package ee.bytecore.backend.integration.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

import ee.bytecore.backend.config.KafkaTestConfiguration;
import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.integration.payment.event.PaymentFailedEvent;
import ee.bytecore.backend.integration.payment.event.PaymentSucceededEvent;
import ee.bytecore.backend.repositories.payment.OrderRepository;
import ee.bytecore.backend.repositories.user.UserRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link PaymentResultListener} against a real Kafka broker
 * (testcontainers): normal success/failure flows, a duplicate/redelivered
 * result event (must be a safe no-op, not corrupt order state), and a
 * malformed payload (must land on the topic's dead-letter topic instead of
 * blocking the consumer or crashing it).
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
@Tag("integration")
class PaymentResultListenerIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Autowired
    UserRepository userRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    private Order createPendingOrder() {
        int n = COUNTER.incrementAndGet();
        User user = userRepository.save(
                User.create("payresult-user-" + n, "payresult-" + n + "@example.com", LocalDate.of(1990, 1, 1)));
        return orderRepository.save(Order.create(user, OrderStatus.PENDING, new BigDecimal("5.00")));
    }

    @Test
    void paymentSucceededTransitionsOrderToPaidTest() throws Exception {
        Order order = createPendingOrder();
        PaymentSucceededEvent event = new PaymentSucceededEvent(
                UUID.randomUUID(), UUID.randomUUID(), order.getId(), UUID.randomUUID(), Instant.now());

        kafkaTemplate
                .send(
                        PaymentTopics.PAYMENT_SUCCEEDED,
                        String.valueOf(order.getId()),
                        OBJECT_MAPPER.writeValueAsString(event))
                .get();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                        orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID));
    }

    @Test
    void duplicatePaymentSucceededEventIsSafeNoOpTest() throws Exception {
        Order order = createPendingOrder();
        PaymentSucceededEvent event = new PaymentSucceededEvent(
                UUID.randomUUID(), UUID.randomUUID(), order.getId(), UUID.randomUUID(), Instant.now());
        String payload = OBJECT_MAPPER.writeValueAsString(event);

        kafkaTemplate
                .send(PaymentTopics.PAYMENT_SUCCEEDED, String.valueOf(order.getId()), payload)
                .get();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                        orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID));

        // Redeliver the exact same result event - PAID -> PAID is a rejected
        // transition; the listener must swallow it, not throw/crash/loop.
        kafkaTemplate
                .send(PaymentTopics.PAYMENT_SUCCEEDED, String.valueOf(order.getId()), payload)
                .get();

        // Give the (no-op) redelivery time to be consumed, then assert the
        // order is still exactly PAID - no corruption, no exception surfaced.
        Thread.sleep(3000);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    @Test
    void paymentFailedCancelsOrderWithReasonTest() throws Exception {
        Order order = createPendingOrder();
        PaymentFailedEvent event = new PaymentFailedEvent(
                UUID.randomUUID(), UUID.randomUUID(), order.getId(), UUID.randomUUID(), "card_declined", Instant.now());

        kafkaTemplate
                .send(
                        PaymentTopics.PAYMENT_FAILED,
                        String.valueOf(order.getId()),
                        OBJECT_MAPPER.writeValueAsString(event))
                .get();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(reloaded.getCancellationReason()).contains("card_declined");
        });
    }

    @Test
    void malformedPaymentSucceededMessageIsRoutedToDeadLetterTopicTest() {
        kafkaTemplate.send(PaymentTopics.PAYMENT_SUCCEEDED, "not-json-at-all");

        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(Collections.singletonList(PaymentTopics.PAYMENT_SUCCEEDED + "-dlt"));

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                boolean found = false;
                for (ConsumerRecord<String, String> record : records) {
                    if ("not-json-at-all".equals(record.value())) {
                        found = true;
                    }
                }
                assertThat(found)
                        .as("malformed message must be dead-lettered instead of blocking the consumer forever")
                        .isTrue();
            });
        }
    }

    private KafkaConsumer<String, String> newConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-consumer-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }
}
