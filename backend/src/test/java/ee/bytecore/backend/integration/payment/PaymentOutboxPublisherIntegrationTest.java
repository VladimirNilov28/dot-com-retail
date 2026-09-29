package ee.bytecore.backend.integration.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import ee.bytecore.backend.config.KafkaTestConfiguration;
import ee.bytecore.backend.config.PostgresTestConfiguration;
import ee.bytecore.backend.entities.payment.Order;
import ee.bytecore.backend.entities.payment.PaymentOutboxEvent;
import ee.bytecore.backend.entities.user.User;
import ee.bytecore.backend.enums.OrderStatus;
import ee.bytecore.backend.repositories.payment.OrderRepository;
import ee.bytecore.backend.repositories.payment.PaymentOutboxEventRepository;
import ee.bytecore.backend.repositories.user.UserRepository;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies the outbox->Kafka publishing side against a real broker
 * (testcontainers) - not a mock producer, so it actually exercises Kafka
 * publication semantics.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
@Tag("integration")
class PaymentOutboxPublisherIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    UserRepository userRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PaymentOutboxEventRepository paymentOutboxEventRepository;

    @Autowired
    PaymentOutboxPublisher paymentOutboxPublisher;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    private Order createOrder() {
        int n = COUNTER.incrementAndGet();
        User user = userRepository.save(User.create(
                "outbox-publisher-user-" + n, "outbox-publisher-" + n + "@example.com", LocalDate.of(1990, 1, 1)));
        return orderRepository.save(Order.create(user, OrderStatus.PENDING, new BigDecimal("1.00")));
    }

    @Test
    void publishesUnpublishedRowToKafkaAndMarksItPublishedTest() {
        Order order = createOrder();
        PaymentOutboxEvent event = paymentOutboxEventRepository.save(PaymentOutboxEvent.create(
                order.getId(),
                PaymentTopics.PAYMENT_REQUESTED,
                "{\"orderId\":" + order.getId() + ",\"marker\":\"publisher-test\"}"));

        paymentOutboxPublisher.publishUnpublished();

        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(Collections.singletonList(PaymentTopics.PAYMENT_REQUESTED));

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                boolean found = false;
                for (ConsumerRecord<String, String> record : records) {
                    if (record.value().contains("publisher-test")) {
                        found = true;
                    }
                }
                assertThat(found)
                        .as("payment.requested must contain the published outbox payload")
                        .isTrue();
            });
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<PaymentOutboxEvent> stillUnpublished =
                    paymentOutboxEventRepository.findAllByPublishedFalseOrderByCreatedAtAsc();
            assertThat(stillUnpublished).noneMatch(row -> row.getId().equals(event.getId()));
        });
    }

    private KafkaConsumer<String, String> newConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }
}
