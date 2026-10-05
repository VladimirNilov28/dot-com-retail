package ee.bytecore.backend.integration.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PaymentKafkaConfigTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PaymentKafkaConfig.class)
            .withBean(DefaultErrorHandler.class, DefaultErrorHandler::new)
            .withPropertyValues(
                    "spring.kafka.bootstrap-servers=127.0.0.1:1",
                    "spring.kafka.consumer.group-id=isolated-config-test",
                    "spring.kafka.consumer.auto-offset-reset=earliest");

    @Test
    void shouldDisableListenerAutoStartupWhenConfiguredTest() {
        assertAutoStartup(context.withPropertyValues("spring.kafka.listener.auto-startup=false"), false);
    }

    @Test
    void shouldEnableListenerAutoStartupByDefaultTest() {
        assertAutoStartup(context, true);
    }

    @Test
    void shouldEnableListenerAutoStartupWhenConfiguredTest() {
        assertAutoStartup(context.withPropertyValues("spring.kafka.listener.auto-startup=true"), true);
    }

    private void assertAutoStartup(ApplicationContextRunner runner, boolean expected) {
        runner.run(application -> {
            assertThat(application).hasNotFailed();
            KafkaListenerContainerFactory<?> factory = application.getBean(KafkaListenerContainerFactory.class);
            assertThat(factory.createContainer("payment-results").isAutoStartup())
                    .isEqualTo(expected);
        });
    }
}
