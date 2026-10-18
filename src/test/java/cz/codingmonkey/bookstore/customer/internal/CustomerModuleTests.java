package cz.codingmonkey.bookstore.customer.internal;

import static cz.codingmonkey.bookstore.customer.TestAddresses.INVOICE;
import static cz.codingmonkey.bookstore.customer.TestAddresses.SHIPPING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.customer.Address;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;

@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class CustomerModuleTests {

    @Autowired
    AddressService service;

    @Autowired
    JdbcTemplate jdbc;

    private static String user() {
        return "cust-" + UUID.randomUUID();
    }

    @Test
    void startsEmpty() {
        String u = user();
        SavedAddressesView view = service.get(u);
        assertThat(view.shipping()).isNull();
        assertThat(view.invoice()).isNull();
        assertThat(service.shipping(u)).isEmpty();
        assertThat(service.invoice(u)).isEmpty();
    }

    @Test
    void shippingAndInvoiceAreKeptApart() {
        String u = user();
        service.saveShipping(u, SHIPPING);
        service.saveInvoice(u, INVOICE);
        SavedAddressesView view = service.get(u);
        assertThat(view.shipping()).isEqualTo(SHIPPING);
        assertThat(view.invoice()).isEqualTo(INVOICE);
        assertThat(service.shipping(u)).contains(SHIPPING);
        assertThat(service.invoice(u)).contains(INVOICE);
    }

    @Test
    void saveReplaces() {
        String u = user();
        service.saveShipping(u, SHIPPING);
        service.saveInvoice(u, INVOICE);
        service.saveShipping(u, INVOICE);
        assertThat(service.get(u).shipping()).isEqualTo(INVOICE);
        assertThat(service.get(u).invoice()).isEqualTo(INVOICE);
    }

    @Test
    void deleteRemovesOnlyThatOne() {
        String u = user();
        service.saveShipping(u, SHIPPING);
        service.saveInvoice(u, INVOICE);
        service.deleteShipping(u);
        assertThat(service.get(u).shipping()).isNull();
        assertThat(service.get(u).invoice()).isEqualTo(INVOICE);
        service.deleteInvoice(u);
        assertThat(service.get(u).invoice()).isNull();
        assertThatCode(() -> {
            service.deleteShipping(user());
            service.deleteInvoice(user());
            service.deleteShipping(u);
        }).doesNotThrowAnyException();
    }

    @Test
    void customersDontSeeEachOther() {
        String a = user();
        String b = user();
        service.saveShipping(a, SHIPPING);
        assertThat(service.get(b).shipping()).isNull();
        assertThat(service.shipping(b)).isEmpty();
        assertThat(service.shipping(a)).contains(SHIPPING);
    }

    @Test
    void parallelFirstSavesBothSucceed() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 20; i++) {
                String u = user();
                CountDownLatch go = new CountDownLatch(1);
                Future<Address> s = pool.submit(() -> {
                    go.await();
                    return service.saveShipping(u, SHIPPING);
                });
                Future<Address> inv = pool.submit(() -> {
                    go.await();
                    return service.saveInvoice(u, INVOICE);
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
                "INSERT INTO customer_address (customer, shipping_name) VALUES (?, 'x')", user()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
