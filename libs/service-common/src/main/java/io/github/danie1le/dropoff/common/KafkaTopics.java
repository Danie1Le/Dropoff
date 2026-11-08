package io.github.danie1le.dropoff.common;

/**
 * Kafka topic names (ticket #32). Order and courier topics are keyed by orderId, so one order's events stay in order on
 * one partition. Each topic has a dead-letter topic named {@code <topic>.DLT}.
 */
public final class KafkaTopics {

    /** OrderPlaced, OrderCancelled, OrderReady, OrderPickedUp and OrderDelivered. */
    public static final String ORDER_EVENTS = "order-events";

    /** CourierAssigned and CourierUnassigned. */
    public static final String COURIER_EVENTS = "courier-events";

    /** CourierLocationUpdated, sent without the outbox. */
    public static final String COURIER_LOCATIONS = "courier-locations";

    /** MerchantChanged, compacted and keyed by merchantId (from M5-3). */
    public static final String MERCHANTS = "merchants";

    /** MenuItemChanged, compacted and keyed by menuItemId (from M5-3). */
    public static final String MENU_ITEMS = "menu-items";

    private KafkaTopics() {}
}
