package io.github.danie1le.dropoff.merchant.internal;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    List<MenuItem> findByMerchantIdOrderById(long merchantId);
}
