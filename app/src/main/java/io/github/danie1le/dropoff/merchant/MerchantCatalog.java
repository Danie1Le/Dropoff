package io.github.danie1le.dropoff.merchant;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Read-only view of merchants and menu items for other modules; the order module validates orders with it. At the
 * service split the order module switches to its local copies instead (ADR-0007).
 */
public interface MerchantCatalog {

    Optional<MerchantView> findMerchant(long merchantId);

    /** The menu items with these ids that exist, in no particular order. Unknown ids are left out. */
    List<MenuItemView> findMenuItems(Collection<Long> menuItemIds);

    record MerchantView(long id, String name, String address, double lat, double lng, boolean open) {}

    record MenuItemView(long id, long merchantId, long priceCents, boolean available) {}
}
