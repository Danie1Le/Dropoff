package io.github.danie1le.dropoff.merchant.internal;

import io.github.danie1le.dropoff.common.DomainException;
import io.github.danie1le.dropoff.merchant.internal.MerchantRequests.CreateMerchant;
import io.github.danie1le.dropoff.merchant.internal.MerchantRequests.UpdateMerchant;
import java.util.Objects;
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
        Merchant merchant = new Merchant(
                request.name().trim(),
                request.address().trim(),
                Objects.requireNonNull(request.lat()),
                Objects.requireNonNull(request.lng()));
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
        String address = request.address();
        if (address != null) {
            // @AddressChangedTogether guarantees lat and lng are present with an address.
            merchant.moveTo(
                    address.trim(), Objects.requireNonNull(request.lat()), Objects.requireNonNull(request.lng()));
        }
        return merchant;
    }

    private static DomainException notFound(long id) {
        return new DomainException(
                DomainException.Category.NOT_FOUND, "MERCHANT_NOT_FOUND", "Merchant " + id + " does not exist.");
    }
}
