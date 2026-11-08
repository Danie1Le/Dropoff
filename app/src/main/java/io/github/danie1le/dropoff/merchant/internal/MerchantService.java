package io.github.danie1le.dropoff.merchant.internal;

import io.github.danie1le.dropoff.common.DbClock;
import io.github.danie1le.dropoff.contracts.MerchantChanged;
import io.github.danie1le.dropoff.merchant.internal.MerchantRequests.CreateMerchant;
import io.github.danie1le.dropoff.merchant.internal.MerchantRequests.UpdateMerchant;
import io.github.danie1le.dropoff.web.DomainException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merchants. Every create and update publishes {@link MerchantChanged} with the whole merchant, which the order module
 * copies (M5-3, ADR-0007).
 */
@Service
class MerchantService {

    private final MerchantRepository merchants;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    MerchantService(MerchantRepository merchants, ApplicationEventPublisher events, Clock clock) {
        this.merchants = merchants;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    Merchant create(CreateMerchant request) {
        Merchant merchant = new Merchant(request.name().trim(), trimmed(request.location()));
        return published(merchants.save(merchant));
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
        return published(merchant);
    }

    /** Publishes the merchant's whole current state, so the latest event alone rebuilds the order module's copy. */
    private Merchant published(Merchant merchant) {
        events.publishEvent(new MerchantChanged(
                UUID.randomUUID(),
                merchant.id(),
                merchant.name(),
                merchant.address(),
                merchant.isOpen(),
                merchant.lat(),
                merchant.lng(),
                DbClock.now(clock)));
        return merchant;
    }

    private static Location trimmed(Location location) {
        return new Location(location.address().trim(), location.lat(), location.lng());
    }

    static DomainException notFound(long merchantId) {
        return new DomainException(
                DomainException.Category.NOT_FOUND,
                "MERCHANT_NOT_FOUND",
                "Merchant " + merchantId + " does not exist.");
    }
}
