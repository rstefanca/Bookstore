package cz.codingmonkey.bookstore.customer.internal;

import cz.codingmonkey.bookstore.customer.Address;
import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.shared.AccountIdType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.EmbeddedColumnNaming;
import org.hibernate.annotations.Type;
import org.jspecify.annotations.Nullable;

/**
 * A customer's saved addresses, one row per customer. Either address may be absent.
 */
@Entity
@Table(name = "customer_address")
class SavedAddresses {

    /** The customer's account. */
    @Id
    @Column(name = "customer_id")
    @Type(AccountIdType.class)
    private AccountId customerId;

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
