package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.order.internal.CustomerRequests.CreateCustomer;
import io.github.danie1le.dropoff.order.internal.CustomerRequests.UpdateCustomer;
import io.github.danie1le.dropoff.web.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CustomerService {

    private final CustomerRepository customers;

    CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    @Transactional
    Customer create(CreateCustomer request) {
        return customers.save(new Customer(request.name().trim(), trimmed(request.location())));
    }

    @Transactional(readOnly = true)
    Customer get(long id) {
        return customers.findById(id).orElseThrow(() -> notFound(id));
    }

    @Transactional
    Customer update(long id, UpdateCustomer request) {
        Customer customer = get(id);
        String name = request.name();
        if (name != null) {
            customer.rename(name.trim());
        }
        Location location = request.location();
        if (location != null) {
            customer.moveTo(trimmed(location));
        }
        return customer;
    }

    private static Location trimmed(Location location) {
        return new Location(location.address().trim(), location.lat(), location.lng());
    }

    static DomainException notFound(long id) {
        return new DomainException(
                DomainException.Category.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer " + id + " does not exist.");
    }
}
