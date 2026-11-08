package io.github.danie1le.dropoff.merchant.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    List<MenuItem> findByMerchantIdOrderById(long merchantId);

    /** {@code SELECT ... FOR UPDATE}: updates of one item take turns, so versions follow commit order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MenuItem> findForUpdateById(long id);
}
