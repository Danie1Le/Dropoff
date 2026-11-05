package io.github.danie1le.dropoff.merchant.internal;

import io.github.danie1le.dropoff.common.DomainException;
import io.github.danie1le.dropoff.merchant.internal.MerchantRequests.CreateMerchant;
import io.github.danie1le.dropoff.merchant.internal.MerchantRequests.UpdateMerchant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MerchantService {

    private final MerchantRepository merchants;

    MerchantService(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    @Transactional
    Merchant create(CreateMerchant request) {
        Merchant merchant = new Merchant(request.name().trim(), trimmed(request.location()));
        return merchants.save(merchant);
    }

    @Transactional(readOnly = true)
    Merchant get(long id) {
        return merchants.findById(id).orElseThrow(() -> notFound(id));
    }

    @Transactional(readOnly = true)
    Page<Merchant> list(Pageable pageable) {
        return merchants.findAll(pageable);
    }

    @Transactional
    Merchant update(long id, UpdateMerchant request) {
        Merchant merchant = get(id);
        String name = request.name();
        if (name != null) {
            merchant.rename(name.trim());
        }
        Boolean open = request.open();
        if (open != null) {
            merchant.setOpen(open);
        }
        Location location = request.location();
        if (location != null) {
            merchant.moveTo(trimmed(location));
        }
        return merchant;
    }

    private static Location trimmed(Location location) {
        return new Location(location.address().trim(), location.lat(), location.lng());
    }

    private static DomainException notFound(long id) {
        return new DomainException(
                DomainException.Category.NOT_FOUND, "MERCHANT_NOT_FOUND", "Merchant " + id + " does not exist.");
    }
}
