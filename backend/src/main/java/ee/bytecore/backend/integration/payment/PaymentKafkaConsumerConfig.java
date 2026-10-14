package ee.bytecore.backend.integration.payment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Error handling for {@code @KafkaListener}s consuming payment result events.
 * A record that keeps failing (malformed payload, unexpected exception) is
 * retried a bounded number of times, then published to the topic's
 * {@code <topic>-dlt} dead-letter topic (this spring-kafka version's
 * default {@link DeadLetterPublishingRecoverer} naming convention)
 * instead of being retried forever or crashing the listener container.
 */
@Configuration
public class PaymentKafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler paymentResultErrorHandler(KafkaOperations<String, String> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations);
        // 3 attempts total (1 initial + 2 retries), 1s apart - bounded, no
        // infinite retry loop for a poison/malformed message.
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));
    }
}
