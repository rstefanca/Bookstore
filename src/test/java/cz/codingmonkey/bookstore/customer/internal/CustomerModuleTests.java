package cz.codingmonkey.bookstore.customer.internal;

import static cz.codingmonkey.bookstore.customer.TestAddresses.INVOICE;
import static cz.codingmonkey.bookstore.customer.TestAddresses.SHIPPING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cz.codingmonkey.bookstore.TestAccountIds;
import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.customer.Address;
import cz.codingmonkey.bookstore.shared.AccountId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;

@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class CustomerModuleTests {

    @Autowired
    AddressService service;

    @Autowired
    CustomerAccountDeletion deletion;

    @Autowired
    JdbcTemplate jdbc;

    private static AccountId user() {
        return TestAccountIds.next();
    }

    @Test
    void removePersonalDataDeletesAddresses() {
        AccountId u = user();
        AccountId other = user();
        service.saveShippingAddress(u, SHIPPING);
        service.saveInvoiceAddress(u, INVOICE);
        service.saveShippingAddress(other, SHIPPING);

        assertThat(deletion.holdsRecords(u)).isFalse();
        deletion.removePersonalData(u);
        deletion.anonymize(u);

        assertThat(service.get(u).shipping()).isNull();
        assertThat(service.get(u).invoice()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_address WHERE customer_id = ?",
                Integer.class, u.value())).isZero();
        assertThat(service.get(other).shipping()).isEqualTo(SHIPPING);
        deletion.removePersonalData(u); // nothing left: still fine
    }

    @Test
    void removingAbsentAddressesLogsNothingAndRemovingPresentOnesDoes(CapturedOutput output) {
        AccountId u = user();
        deletion.removePersonalData(u);
        assertThat(output.getAll()).doesNotContain("Removed the saved addresses of account " + u);

        service.saveShippingAddress(u, SHIPPING);
        deletion.removePersonalData(u);
        assertThat(output.getAll()).contains("Removed the saved addresses of account " + u);
    }

    @Test
    void startsEmpty() {
        AccountId u = user();
        SavedAddressesView view = service.get(u);
        assertThat(view.shipping()).isNull();
        assertThat(view.invoice()).isNull();
        assertThat(service.getShippingAddress(u)).isEmpty();
        assertThat(service.getInvoiceAddress(u)).isEmpty();
    }

    @Test
    void shippingAndInvoiceAreKeptApart() {
        AccountId u = user();
        service.saveShippingAddress(u, SHIPPING);
        service.saveInvoiceAddress(u, INVOICE);
        SavedAddressesView view = service.get(u);
        assertThat(view.shipping()).isEqualTo(SHIPPING);
        assertThat(view.invoice()).isEqualTo(INVOICE);
        assertThat(service.getShippingAddress(u)).contains(SHIPPING);
        assertThat(service.getInvoiceAddress(u)).contains(INVOICE);
    }

    @Test
    void saveReplaces() {
        AccountId u = user();
        service.saveShippingAddress(u, SHIPPING);
        service.saveInvoiceAddress(u, INVOICE);
        service.saveShippingAddress(u, INVOICE);
        assertThat(service.get(u).shipping()).isEqualTo(INVOICE);
        assertThat(service.get(u).invoice()).isEqualTo(INVOICE);
    }

    @Test
    void deleteRemovesOnlyThatOne() {
        AccountId u = user();
        service.saveShippingAddress(u, SHIPPING);
        service.saveInvoiceAddress(u, INVOICE);
        service.deleteShippingAddress(u);
        assertThat(service.get(u).shipping()).isNull();
        assertThat(service.get(u).invoice()).isEqualTo(INVOICE);
        service.deleteInvoiceAddress(u);
        assertThat(service.get(u).invoice()).isNull();
        assertThatCode(() -> {
            service.deleteShippingAddress(user());
            service.deleteInvoiceAddress(user());
            service.deleteShippingAddress(u);
        }).doesNotThrowAnyException();
    }

    @Test
    void customersDontSeeEachOther() {
        AccountId a = user();
        AccountId b = user();
        service.saveShippingAddress(a, SHIPPING);
        assertThat(service.get(b).shipping()).isNull();
        assertThat(service.getShippingAddress(b)).isEmpty();
        assertThat(service.getShippingAddress(a)).contains(SHIPPING);
    }

    @Test
    void parallelFirstSavesBothSucceed() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 20; i++) {
                AccountId u = user();
                CountDownLatch go = new CountDownLatch(1);
                Future<Address> s = pool.submit(() -> {
                    go.await();
                    return service.saveShippingAddress(u, SHIPPING);
                });
                Future<Address> inv = pool.submit(() -> {
                    go.await();
                    return service.saveInvoiceAddress(u, INVOICE);
                });
                go.countDown();
                s.get();
                inv.get();
                assertThat(service.get(u).shipping()).isEqualTo(SHIPPING);
                assertThat(service.get(u).invoice()).isEqualTo(INVOICE);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void halfAnAddressCannotBeStored() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO customer_address (customer_id, shipping_name) VALUES (?, 'x')", user().value()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
