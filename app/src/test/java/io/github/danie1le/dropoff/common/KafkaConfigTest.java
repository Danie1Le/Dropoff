package io.github.danie1le.dropoff.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.contracts.OrderReady;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

class KafkaConfigTest {

    @Test
    void retriesThreeTimesOneTwoAndFourSecondsApartThenGivesUp() {
        BackOff backOff = KafkaConfig.retryBackOff();
        BackOffExecution execution = backOff.start();

        assertThat(execution.nextBackOff()).isEqualTo(1000);
        assertThat(execution.nextBackOff()).isEqualTo(2000);
        assertThat(execution.nextBackOff()).isEqualTo(4000);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void namesEveryEventTypeBySimpleName() {
        assertThat(KafkaConfig.typeNames())
                .containsEntry("OrderReady", OrderReady.class)
                .hasSize(KafkaConfig.EVENTS.size());
    }
}
