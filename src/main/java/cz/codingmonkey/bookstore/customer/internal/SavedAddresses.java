package cz.codingmonkey.bookstore.customer.internal;

import cz.codingmonkey.bookstore.customer.Address;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.EmbeddedColumnNaming;
import org.jspecify.annotations.Nullable;

/**
 * A customer's saved addresses, one row per customer. Either address may be absent.
 */
@Entity
@Table(name = "customer_address")
class SavedAddresses {

    /** The customer's username. */
    @Id
    private String customer;

    @Embedded
    @EmbeddedColumnNaming("shipping_%s")
    private @Nullable Address shipping;

    @Embedded
    @EmbeddedColumnNaming("invoice_%s")
    private @Nullable Address invoice;

    protected SavedAddresses() {
    }

    @Nullable Address shipping() {
        return shipping;
    }

    @Nullable Address invoice() {
        return invoice;
    }

    void shipping(@Nullable Address shipping) {
        this.shipping = shipping;
    }

    void invoice(@Nullable Address invoice) {
        this.invoice = invoice;
    }
}
