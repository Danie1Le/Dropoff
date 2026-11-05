package io.github.danie1le.dropoff.merchant.internal;

import io.github.danie1le.dropoff.common.DomainException;
import io.github.danie1le.dropoff.merchant.internal.MenuItemRequests.CreateMenuItem;
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

    private static DomainException notFound(long id) {
        return new DomainException(
                DomainException.Category.NOT_FOUND, "MENU_ITEM_NOT_FOUND", "Menu item " + id + " does not exist.");
    }
}
