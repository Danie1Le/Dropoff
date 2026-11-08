package io.github.danie1le.dropoff.merchant.internal;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface MerchantRepository extends JpaRepository<Merchant, Long> {

    /** {@code SELECT ... FOR UPDATE}: updates of one merchant take turns, so versions follow commit order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Merchant> findForUpdateById(long id);
}
