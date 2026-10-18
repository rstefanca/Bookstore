package cz.codingmonkey.bookstore.customer.internal;

import cz.codingmonkey.bookstore.customer.Address;
import cz.codingmonkey.bookstore.customer.CustomerAddresses;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class AddressService implements CustomerAddresses {

    private static final ChangeLog changes = ChangeLog.of(AddressService.class);

    private final SavedAddressesRepository repository;

    AddressService(SavedAddressesRepository repository) {
        this.repository = repository;
    }

    SavedAddressesView get(String customer) {
        return repository.findById(customer)
                .map(s -> new SavedAddressesView(s.shipping(), s.invoice()))
                .orElseGet(() -> new SavedAddressesView(null, null));
    }

    @Override
    public Optional<Address> shipping(String username) {
        return repository.findById(username).map(SavedAddresses::shipping);
    }

    @Override
    public Optional<Address> invoice(String username) {
        return repository.findById(username).map(SavedAddresses::invoice);
    }

    @Transactional
    Address saveShipping(String customer, Address address) {
        lock(customer).shipping(address);
        changes.info("Customer {} saved a shipping address", customer);
        return address;
    }

    @Transactional
    Address saveInvoice(String customer, Address address) {
        lock(customer).invoice(address);
        changes.info("Customer {} saved an invoice address", customer);
        return address;
    }

    @Transactional
    void deleteShipping(String customer) {
        lock(customer).shipping(null);
        changes.info("Customer {} removed the shipping address", customer);
    }

    @Transactional
    void deleteInvoice(String customer) {
        lock(customer).invoice(null);
        changes.info("Customer {} removed the invoice address", customer);
    }

    private SavedAddresses lock(String customer) {
        repository.insertIfAbsent(customer);
        return repository.findAndLockByCustomer(customer).orElseThrow();
    }
}
