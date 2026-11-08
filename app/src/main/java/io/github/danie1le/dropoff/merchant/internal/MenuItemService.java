package io.github.danie1le.dropoff.merchant.internal;

import io.github.danie1le.dropoff.merchant.internal.MenuItemRequests.CreateMenuItem;
import io.github.danie1le.dropoff.merchant.internal.MenuItemRequests.UpdateMenuItem;
import io.github.danie1le.dropoff.web.DomainException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MenuItemService {

    private final MenuItemRepository items;
    private final MerchantService merchants;

    MenuItemService(MenuItemRepository items, MerchantService merchants) {
        this.items = items;
        this.merchants = merchants;
    }

    /** Adds an available item. A closed merchant can still add items (ticket #58). */
    @Transactional
    MenuItem create(long merchantId, CreateMenuItem request) {
        Merchant merchant = merchants.get(merchantId);
        return items.save(new MenuItem(merchant.id(), request.trimmedName(), request.priceCentsValue()));
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
        return item;
    }

    private static DomainException notFound(long id) {
        return new DomainException(
                DomainException.Category.NOT_FOUND, "MENU_ITEM_NOT_FOUND", "Menu item " + id + " does not exist.");
    }
}
