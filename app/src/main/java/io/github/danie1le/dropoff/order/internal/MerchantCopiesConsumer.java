package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.KafkaTopics;
import io.github.danie1le.dropoff.contracts.MenuItemChanged;
import io.github.danie1le.dropoff.contracts.MerchantChanged;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

/**
 * Fills the order module's merchant and menu item copies from the compacted {@code merchants} and {@code menu-items}
 * topics, as the {@code order-service} group (M5-3, tickets #16, #32, ADR-0007).
 *
 * <p>When a copy table is empty as its topic's partitions are assigned (a fresh database, say, while the group still
 * has offsets from before), the listener seeks those partitions back to offset 0. Compaction keeps at least the latest
 * record per id, so reading the topic from the start rebuilds the whole table.
 *
 * <p>No {@link io.github.danie1le.dropoff.common.ProcessedEvents} dedupe: a rebuild must apply events this group has
 * already seen, and an upsert of the whole entity is safe to apply twice.
 */
@Component
class MerchantCopiesConsumer implements ConsumerSeekAware {

    static final String MERCHANTS_LISTENER = "merchant-copies";
    static final String MENU_ITEMS_LISTENER = "menu-item-copies";

    private static final Logger log = LoggerFactory.getLogger(MerchantCopiesConsumer.class);

    private final MerchantCopies copies;

    MerchantCopiesConsumer(MerchantCopies copies) {
        this.copies = copies;
    }

    @KafkaListener(id = MERCHANTS_LISTENER, topics = KafkaTopics.MERCHANTS, groupId = CourierEventsConsumer.GROUP)
    void on(MerchantChanged event) {
        copies.save(event);
    }

    @KafkaListener(id = MENU_ITEMS_LISTENER, topics = KafkaTopics.MENU_ITEMS, groupId = CourierEventsConsumer.GROUP)
    void on(MenuItemChanged event) {
        copies.save(event);
    }

    /** Called on the consumer thread of each listener, with the partitions just assigned to it. */
    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        Map<String, List<TopicPartition>> byTopic =
                assignments.keySet().stream().collect(Collectors.groupingBy(TopicPartition::topic));
        byTopic.forEach((topic, partitions) -> {
            if (isEmptyCopy(topic)) {
                log.info("The copy of {} is empty: replaying {} from offset 0", topic, partitions);
                callback.seekToBeginning(partitions);
            }
        });
    }

    private boolean isEmptyCopy(String topic) {
        return switch (topic) {
            case KafkaTopics.MERCHANTS -> copies.noMerchants();
            case KafkaTopics.MENU_ITEMS -> copies.noMenuItems();
            default -> false;
        };
    }
}
