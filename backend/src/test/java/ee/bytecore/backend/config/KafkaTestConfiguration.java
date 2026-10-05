package ee.bytecore.backend.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

import org.testcontainers.kafka.KafkaContainer;

/**
 * There is no Kafka auto-configuration on this project's Spring Boot 4 /
 * spring-kafka classpath (see {@link PaymentKafkaConfig}), so
 * {@code @ServiceConnection} has nothing to bind - it only wires beans that
 * consume {@code KafkaConnectionDetails}, which don't exist here. Instead,
 * the container's bootstrap address is pushed directly into the
 * {@code spring.kafka.bootstrap-servers} property that the manual
 * {@code PaymentKafkaConfig} beans read.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestConfiguration {
    @Bean
    KafkaContainer kafkaContainer() {
        return new KafkaContainer("apache/kafka:3.9.1");
    }

    @Bean
    DynamicPropertyRegistrar kafkaDynamicProperties(KafkaContainer kafkaContainer) {
        return registry -> registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }
}
