package io.github.danie1le.dropoff.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.contracts.MenuItemChanged;
import io.github.danie1le.dropoff.contracts.MerchantChanged;
import io.github.danie1le.dropoff.contracts.OrderReady;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
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
                .containsEntry("MerchantChanged", MerchantChanged.class)
                .containsEntry("MenuItemChanged", MenuItemChanged.class)
                .hasSize(KafkaConfig.EVENTS.size());
    }

    /** Compacted topics keep the latest record per key, so each entity is keyed by its own id (ticket #16). */
    @Test
    void routesMerchantChangesToTheMerchantsTopicKeyedByMerchantId() {
        EventExternalizationConfiguration routes = new KafkaConfig().eventExternalization();
        MerchantChanged event = new MerchantChanged(
                UUID.randomUUID(), 7, "Taco Shop", "100 Congress Ave", true, 30.2, -97.7, Instant.EPOCH);

        assertThat(routes.supports(event)).isTrue();
        RoutingTarget target = routes.determineTarget(event);
        assertThat(target.getTarget()).isEqualTo(KafkaTopics.MERCHANTS);
        assertThat(target.getKey()).isEqualTo("7");
    }

    @Test
    void routesMenuItemChangesToTheMenuItemsTopicKeyedByMenuItemId() {
        EventExternalizationConfiguration routes = new KafkaConfig().eventExternalization();
        MenuItemChanged event = new MenuItemChanged(UUID.randomUUID(), 42, 7, "Taco", 399, true, Instant.EPOCH);

        assertThat(routes.supports(event)).isTrue();
        RoutingTarget target = routes.determineTarget(event);
        assertThat(target.getTarget()).isEqualTo(KafkaTopics.MENU_ITEMS);
        assertThat(target.getKey()).isEqualTo("42");
    }
}
