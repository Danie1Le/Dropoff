package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.contracts.MenuItemChanged;
import io.github.danie1le.dropoff.contracts.MerchantChanged;
import io.github.danie1le.dropoff.web.DomainException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The order module's copies of merchants and menu items (M5-3, ADR-0007), kept current from merchant's events. The
 * order module reads merchants and items only from here, never from the merchant module.
 *
 * <p>Each event carries the whole entity, so a write is an upsert: insert the row, or overwrite it if the id is already
 * there. Applying an event twice leaves the same row, so the consumer needs no dedupe. Events can arrive out of order,
 * so the overwrite happens only when the event's version is higher than the copy's: an older event never undoes a
 * newer one.
 */
@Repository
class MerchantCopies {

    record MerchantCopy(long id, String name, String address, boolean open, double lat, double lng) {}

    record MenuItemCopy(long id, long merchantId, String name, long priceCents, boolean available) {}

    private final JdbcClient jdbc;

    MerchantCopies(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void save(MerchantChanged event) {
        jdbc.sql("INSERT INTO merchant_copies (merchant_id, name, address, open, lat, lng, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (merchant_id) DO UPDATE SET"
                        + " name = EXCLUDED.name, address = EXCLUDED.address, open = EXCLUDED.open,"
                        + " lat = EXCLUDED.lat, lng = EXCLUDED.lng, version = EXCLUDED.version"
                        + " WHERE merchant_copies.version < EXCLUDED.version")
                .params(
                        event.merchantId(),
                        event.name(),
                        event.address(),
                        event.open(),
                        event.lat(),
                        event.lng(),
                        event.version())
                .update();
    }

    void save(MenuItemChanged event) {
        jdbc.sql("INSERT INTO menu_item_copies (menu_item_id, merchant_id, name, price_cents, available, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (menu_item_id) DO UPDATE SET"
                        + " merchant_id = EXCLUDED.merchant_id, name = EXCLUDED.name,"
                        + " price_cents = EXCLUDED.price_cents, available = EXCLUDED.available,"
                        + " version = EXCLUDED.version WHERE menu_item_copies.version < EXCLUDED.version")
                .params(
                        event.menuItemId(),
                        event.merchantId(),
                        event.name(),
                        event.priceCents(),
                        event.available(),
                        event.version())
                .update();
    }

    Optional<MerchantCopy> findMerchant(long merchantId) {
        return jdbc.sql("SELECT merchant_id AS id, name, address, open, lat, lng FROM merchant_copies"
                        + " WHERE merchant_id = ?")
                .param(merchantId)
                .query(MerchantCopy.class)
                .optional();
    }

    boolean merchantExists(long merchantId) {
        return findMerchant(merchantId).isPresent();
    }

    /** The items with these ids that are copied, in no particular order. Unknown ids are left out. */
    List<MenuItemCopy> findMenuItems(Collection<Long> menuItemIds) {
        if (menuItemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT menu_item_id AS id, merchant_id, name, price_cents, available FROM menu_item_copies"
                        + " WHERE menu_item_id IN (:ids)")
                .param("ids", List.copyOf(menuItemIds))
                .query(MenuItemCopy.class)
                .list();
    }

    boolean noMerchants() {
        return isEmpty("merchant_copies");
    }

    boolean noMenuItems() {
        return isEmpty("menu_item_copies");
    }

    private boolean isEmpty(String table) {
        return !jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + table + ")")
                .query(Boolean.class)
                .single();
    }

    /** The 404 for a merchant named in the URL path that the order module has no copy of. */
    static DomainException notFound(long merchantId) {
        return new DomainException(
                DomainException.Category.NOT_FOUND,
                "MERCHANT_NOT_FOUND",
                "Merchant " + merchantId + " does not exist.");
    }
}
