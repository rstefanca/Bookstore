package cz.codingmonkey.bookstore.storefront;

import cz.codingmonkey.bookstore.shared.CurrentUser;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's wishlist as a list of books; adding and removing books is in the wishlist module.
 */
@RestController
@Tag(name = "Wishlist")
class WishlistViewController {

    private final StorefrontService service;

    WishlistViewController(StorefrontService service) {
        this.service = service;
    }

    /**
     * Lists your wishlist, newest added first, with each book's price and rating.
     * <p>
     * A book without a price is listed with a {@code null} price. The {@code sort} parameter is ignored.
     */
    @GetMapping("/api/me/wishlist")
    @ApiResponse(responseCode = "200", description = "A page of your wishlist; empty if there is nothing on it")
    PagedModel<WishlistedBook> list(Pageable page) {
        return new PagedModel<>(service.findWishlist(CurrentUser.requireId(), page));
    }
}
