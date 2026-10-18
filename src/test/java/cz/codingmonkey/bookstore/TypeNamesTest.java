package cz.codingmonkey.bookstore;

import static org.assertj.core.api.Assertions.assertThat;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import cz.codingmonkey.bookstore.inventory.ReservationId;
import cz.codingmonkey.bookstore.ordering.OrderId;
import cz.codingmonkey.bookstore.payment.PaymentReference;
import cz.codingmonkey.bookstore.shared.AccountId;
import org.junit.jupiter.api.Test;

/** typeName() is stored in the audit log: renaming an id class changes stored audit data. */
class TypeNamesTest {

    @Test
    void typeNamesAreDerivedFromTheClassName() {
        assertThat(new BookId(1).typeName()).isEqualTo("BOOK");
        assertThat(new AuthorId(1).typeName()).isEqualTo("AUTHOR");
        assertThat(new OrderId(1).typeName()).isEqualTo("ORDER");
        assertThat(new GenreId(1).typeName()).isEqualTo("GENRE");
        assertThat(new AccountId(1).typeName()).isEqualTo("ACCOUNT");
        assertThat(new ReservationId(1).typeName()).isEqualTo("RESERVATION");
        assertThat(new PaymentReference(1).typeName()).as("no Id suffix, two words").isEqualTo("PAYMENT_REFERENCE");
    }
}
