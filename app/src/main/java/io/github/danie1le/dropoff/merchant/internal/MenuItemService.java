package io.github.danie1le.dropoff.merchant.internal;

import io.github.danie1le.dropoff.common.DbClock;
import io.github.danie1le.dropoff.contracts.MenuItemChanged;
import io.github.danie1le.dropoff.merchant.internal.MenuItemRequests.CreateMenuItem;
import io.github.danie1le.dropoff.merchant.internal.MenuItemRequests.UpdateMenuItem;
import io.github.danie1le.dropoff.web.DomainException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Menu items. Every create and update publishes {@link MenuItemChanged} with the whole item, which the order module
 * copies to validate and price orders (M5-3, ADR-0007).
 */
@Service
class MenuItemService {

    private final MenuItemRepository items;
    private final MerchantService merchants;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    MenuItemService(
            MenuItemRepository items, MerchantService merchants, ApplicationEventPublisher events, Clock clock) {
        this.items = items;
        this.merchants = merchants;
        this.events = events;
        this.clock = clock;
    }

    /** Adds an available item. A closed merchant can still add items (ticket #58). */
    @Transactional
    MenuItem create(long merchantId, CreateMenuItem request) {
        Merchant merchant = merchants.get(merchantId);
        return published(items.save(new MenuItem(merchant.id(), request.trimmedName(), request.priceCentsValue())));
    }

    @Transactional(readOnly = true)
    MenuItem get(long id) {
        return items.findById(id).orElseThrow(() -> notFound(id));
    }

    /** The whole menu, available or not, ordered by id. A closed merchant's menu is still readable. */
    @Transactional(readOnly = true)
    List<MenuItem> menu(long merchantId) {
        Merchant merchant = merchants.get(merchantId);
        return items.findByMerchantIdOrderById(merchant.id());
    }

    /** Changes only the fields the request gives. Items are never deleted (ticket #61). */
    @Transactional
    MenuItem update(long id, UpdateMenuItem request) {
        MenuItem item = get(id);
        String name = request.trimmedName();
        if (name != null) {
            item.rename(name);
        }
        Long priceCents = request.priceCentsValue();
        if (priceCents != null) {
            item.reprice(priceCents);
        }
        Boolean available = request.available();
        if (available != null) {
            item.changeAvailability(available);
        }
        return published(item);
    }

    /** Publishes the item's whole current state, so the latest event alone rebuilds the order module's copy. */
    private MenuItem published(MenuItem item) {
        events.publishEvent(new MenuItemChanged(
                UUID.randomUUID(),
                item.id(),
                item.merchantId(),
                item.name(),
                item.priceCents(),
                item.isAvailable(),
                DbClock.now(clock)));
        return item;
    }

    private static DomainException notFound(long id) {
        return new DomainException(
                DomainException.Category.NOT_FOUND, "MENU_ITEM_NOT_FOUND", "Menu item " + id + " does not exist.");
    }
}
