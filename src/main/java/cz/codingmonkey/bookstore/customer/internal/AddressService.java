package cz.codingmonkey.bookstore.customer.internal;

import cz.codingmonkey.bookstore.customer.Address;
import cz.codingmonkey.bookstore.customer.CustomerAddresses;
import cz.codingmonkey.bookstore.shared.AccountId;
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

    SavedAddressesView get(AccountId customer) {
        return repository.findById(customer)
                .map(s -> new SavedAddressesView(s.shipping(), s.invoice()))
                .orElseGet(() -> new SavedAddressesView(null, null));
    }

    @Override
    public Optional<Address> getShippingAddress(AccountId customer) {
        return repository.findById(customer).map(SavedAddresses::shipping);
    }

    @Override
    public Optional<Address> getInvoiceAddress(AccountId customer) {
        return repository.findById(customer).map(SavedAddresses::invoice);
    }

    @Transactional
    Address saveShippingAddress(AccountId customer, Address address) {
        getOrCreateAdressesForUpdateLocking(customer).shipping(address);
        changes.info("Account {} saved a shipping address", customer);
        return address;
    }

    @Transactional
    Address saveInvoiceAddress(AccountId customer, Address address) {
        getOrCreateAdressesForUpdateLocking(customer).invoice(address);
        changes.info("Account {} saved an invoice address", customer);
        return address;
    }

    @Transactional
    void deleteShippingAddress(AccountId customer) {
        getOrCreateAdressesForUpdateLocking(customer).shipping(null);
        changes.info("Account {} removed the shipping address", customer);
    }

    @Transactional
    void deleteInvoiceAddress(AccountId customer) {
        getOrCreateAdressesForUpdateLocking(customer).invoice(null);
        changes.info("Account {} removed the invoice address", customer);
    }

    private SavedAddresses getOrCreateAdressesForUpdateLocking(AccountId customer) {
        repository.insertIfAbsent(customer.value());
        return repository.findAndLockByCustomerId(customer).orElseThrow();
    }
}
