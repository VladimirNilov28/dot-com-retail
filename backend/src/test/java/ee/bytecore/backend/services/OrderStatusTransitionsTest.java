package ee.bytecore.backend.services;

import static org.assertj.core.api.Assertions.assertThat;

import ee.bytecore.backend.enums.OrderStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class OrderStatusTransitionsTest {

    @ParameterizedTest
    @CsvSource({
        "PENDING,PAID,true",
        "PENDING,CANCELLED,true",
        "PAID,PROCESSING,true",
        "PAID,CANCELLED,true",
        "PROCESSING,SHIPPING,true",
        "SHIPPING,COMPLETED,true",
        "PAID,SHIPPING,false",
        "PROCESSING,CANCELLED,false",
        "PROCESSING,COMPLETED,false",
        "PROCESSING,PAID,false",
        "PENDING,PROCESSING,false",
        "PENDING,SHIPPING,false",
        "PENDING,COMPLETED,false",
        "PAID,COMPLETED,false",
        "PAID,PENDING,false",
        "SHIPPING,PENDING,false",
        "SHIPPING,PAID,false",
        "SHIPPING,CANCELLED,false",
        "COMPLETED,CANCELLED,false",
        "COMPLETED,PENDING,false",
        "CANCELLED,PENDING,false",
        "CANCELLED,PAID,false",
    })
    void shouldEvaluateTransitionAccordingToGraphTest(OrderStatus from, OrderStatus to, boolean expected) {
        assertThat(OrderStatusTransitions.canTransition(from, to)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void shouldRejectSameStatusTransitionTest(OrderStatus status) {
        assertThat(OrderStatusTransitions.canTransition(status, status)).isFalse();
    }

    @Test
    void shouldTreatCompletedAsTerminalTest() {
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(OrderStatusTransitions.canTransition(OrderStatus.COMPLETED, target))
                    .isFalse();
        }
    }

    @Test
    void shouldTreatCancelledAsTerminalTest() {
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(OrderStatusTransitions.canTransition(OrderStatus.CANCELLED, target))
                    .isFalse();
        }
    }
}
