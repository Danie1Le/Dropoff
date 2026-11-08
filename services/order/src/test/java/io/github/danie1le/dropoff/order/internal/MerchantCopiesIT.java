package io.github.danie1le.dropoff.order.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.Consumers;
import io.github.danie1le.dropoff.KafkaTestSupport;
import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.common.KafkaTopics;
import io.github.danie1le.dropoff.contracts.MenuItemChanged;
import io.github.danie1le.dropoff.contracts.MerchantChanged;
import io.github.danie1le.dropoff.order.internal.MerchantCopies.MenuItemCopy;
import io.github.danie1le.dropoff.order.internal.MerchantCopies.MerchantCopy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The order module fills its merchant and menu item copies from the compacted topics, and rebuilds them from offset 0
 * when they are empty (M5-3, ADR-0007). Events are sent straight to the topics, as merchant-service will send them
 * after the split, with ids no other test uses: every test context shares one broker, and a context that rebuilds its
 * copies reads these records too.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MerchantCopiesIT {

    private static final Duration WAIT = Duration.ofSeconds(15);

    /** The lowest id this class uses, far above any sequence value. */
    private static final long MIN_ID = 1_000_000_000L;

    private final JdbcClient jdbc;
    private final MerchantCopies copies;
    private final JsonMapper json;
    private final KafkaListenerEndpointRegistry listeners;

    MerchantCopiesIT(
            @Autowired JdbcClient jdbc,
            @Autowired MerchantCopies copies,
            @Autowired JsonMapper json,
            @Autowired KafkaListenerEndpointRegistry listeners) {
        this.jdbc = jdbc;
        this.copies = copies;
        this.json = json;
        this.listeners = listeners;
    }

    /**
     * Deletes every copy in this class's id range, not only this test's: a rebuild replays the records every test of
     * this class has sent.
     */
    @AfterEach
    void cleanUp() {
        Consumers.awaitSettled(jdbc);
        jdbc.sql("DELETE FROM merchant_copies WHERE merchant_id >= ?")
                .param(MIN_ID)
                .update();
        jdbc.sql("DELETE FROM menu_item_copies WHERE menu_item_id >= ?")
                .param(MIN_ID)
                .update();
    }

    /** An id far above any sequence value, so it collides with no fixture row in any test context. */
    private static long unusedId() {
        return ThreadLocalRandom.current().nextLong(MIN_ID, 1_000_000_000_000L);
    }

    private void send(MerchantChanged event) {
        KafkaTestSupport.send(
                KafkaTopics.MERCHANTS,
                Long.toString(event.merchantId()),
                "MerchantChanged",
                json.writeValueAsBytes(event));
    }

    private void send(MenuItemChanged event) {
        KafkaTestSupport.send(
                KafkaTopics.MENU_ITEMS,
                Long.toString(event.menuItemId()),
                "MenuItemChanged",
                json.writeValueAsBytes(event));
    }

    private static MerchantChanged merchant(long id, String name, boolean open, long version) {
        return new MerchantChanged(
                UUID.randomUUID(), id, name, "100 Congress Ave", open, 30.2672, -97.7431, version, Instant.now());
    }

    private static MenuItemChanged item(long id, long merchantId, long priceCents, boolean available, long version) {
        return new MenuItemChanged(
                UUID.randomUUID(), id, merchantId, "Taco", priceCents, available, version, Instant.now());
    }

    private MerchantCopy awaitMerchant(long id, boolean open) {
        return Awaitility.await()
                .atMost(WAIT)
                .until(
                        () -> copies.findMerchant(id),
                        copy -> copy.isPresent() && copy.get().open() == open)
                .orElseThrow();
    }

    private MenuItemCopy awaitItem(long id, long priceCents) {
        return Awaitility.await()
                .atMost(WAIT)
                .until(
                        () -> copies.findMenuItems(List.of(id)).stream().findFirst(),
                        (Optional<MenuItemCopy> copy) ->
                                copy.isPresent() && copy.get().priceCents() == priceCents)
                .orElseThrow();
    }

    @Test
    void copiesMerchantsAndMenuItemsAndOverwritesThemOnEachChange() {
        long merchantId = unusedId();
        long itemId = unusedId();

        send(merchant(merchantId, "Taco Shop", true, 1));
        send(item(itemId, merchantId, 399, true, 1));

        assertThat(awaitMerchant(merchantId, true))
                .isEqualTo(new MerchantCopy(merchantId, "Taco Shop", "100 Congress Ave", true, 30.2672, -97.7431));
        assertThat(awaitItem(itemId, 399)).isEqualTo(new MenuItemCopy(itemId, merchantId, "Taco", 399, true));

        send(merchant(merchantId, "Taco Truck", false, 2));
        send(item(itemId, merchantId, 450, false, 2));

        assertThat(awaitMerchant(merchantId, false).name()).isEqualTo("Taco Truck");
        assertThat(awaitItem(itemId, 450).available()).isFalse();
    }

    /**
     * Records can arrive out of order (async externalization, a republish on restart, a DLT replay). An older version
     * read after a newer one leaves the copy alone.
     */
    @Test
    void keepsTheNewerVersionWhenAnOlderOneArrivesLater() {
        long merchantId = unusedId();
        long itemId = unusedId();
        send(merchant(merchantId, "Taco Shop", false, 2));
        send(item(itemId, merchantId, 450, false, 2));
        awaitMerchant(merchantId, false);
        awaitItem(itemId, 450);

        send(merchant(merchantId, "Taco Shop", true, 1));
        send(item(itemId, merchantId, 399, true, 1));
        Consumers.awaitSettled(jdbc);

        assertThat(copies.findMerchant(merchantId).orElseThrow().open()).isFalse();
        assertThat(copies.findMenuItems(List.of(itemId)))
                .containsExactly(new MenuItemCopy(itemId, merchantId, "Taco", 450, false));
    }

    /**
     * The consumers have already read every record, so only a seek back to offset 0 can bring the deleted rows back. A
     * merchant changed twice comes back as its highest version.
     */
    @Test
    void rebuildsEmptyCopiesFromOffsetZero() {
        long merchantId = unusedId();
        long itemId = unusedId();
        send(merchant(merchantId, "Taco Shop", true, 1));
        send(merchant(merchantId, "Taco Shop", false, 2));
        send(item(itemId, merchantId, 399, true, 1));
        awaitMerchant(merchantId, false);
        awaitItem(itemId, 399);
        Consumers.awaitSettled(jdbc);

        List<MessageListenerContainer> containers = List.of(
                listener(MerchantCopiesConsumer.MERCHANTS_LISTENER),
                listener(MerchantCopiesConsumer.MENU_ITEMS_LISTENER));
        containers.forEach(MessageListenerContainer::stop);
        jdbc.sql("DELETE FROM merchant_copies").update();
        jdbc.sql("DELETE FROM menu_item_copies").update();
        assertThat(copies.noMerchants()).isTrue();
        assertThat(copies.noMenuItems()).isTrue();
        containers.forEach(MessageListenerContainer::start);

        assertThat(awaitMerchant(merchantId, false).name()).isEqualTo("Taco Shop");
        assertThat(awaitItem(itemId, 399)).isEqualTo(new MenuItemCopy(itemId, merchantId, "Taco", 399, true));
    }

    private MessageListenerContainer listener(String id) {
        return Objects.requireNonNull(listeners.getListenerContainer(id), id);
    }
}
