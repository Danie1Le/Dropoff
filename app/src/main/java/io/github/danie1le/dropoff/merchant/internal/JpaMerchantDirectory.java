package io.github.danie1le.dropoff.merchant.internal;

import io.github.danie1le.dropoff.merchant.MerchantDirectory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaMerchantDirectory implements MerchantDirectory {

    private final MerchantRepository merchants;
    private final MenuItemRepository items;

    JpaMerchantDirectory(MerchantRepository merchants, MenuItemRepository items) {
        this.merchants = merchants;
        this.items = items;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MerchantView> findMerchant(long merchantId) {
        return merchants
                .findById(merchantId)
                .map(m -> new MerchantView(m.id(), m.name(), m.address(), m.lat(), m.lng(), m.isOpen()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MenuItemView> findMenuItems(Collection<Long> menuItemIds) {
        return items.findAllById(menuItemIds).stream()
                .map(i -> new MenuItemView(i.id(), i.merchantId(), i.priceCents(), i.isAvailable()))
                .toList();
    }
}
