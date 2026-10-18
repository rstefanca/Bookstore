package cz.codingmonkey.bookstore.wishlist;

import cz.codingmonkey.bookstore.shared.AccountId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Read access to customers' wishlists for other modules. */
public interface Wishlist {

    /**
     * The books on the account's wishlist, newest added first (then by book id). The page's sort is ignored.
     */
    Page<WishlistEntry> entriesOf(AccountId account, Pageable page);
}
