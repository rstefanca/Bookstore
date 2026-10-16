package cz.codingmonkey.bookstore.storefront;

import java.math.BigDecimal;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookSearch;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer-facing reads of books, with prices. Changes to books stay with the catalog's API.
 */
@RestController
@RequestMapping("/api/books")
class StorefrontController {

    private final StorefrontService service;

    StorefrontController(StorefrontService service) {
        this.service = service;
    }

    /**
     * Books sorted by title; filters are optional and combine with AND. Price bounds are inclusive, in the store
     * currency. A {@code sort} parameter is ignored.
     */
    @GetMapping
    PagedModel<StorefrontBook> list(@RequestParam(required = false) String title,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) AuthorId authorId,
            @RequestParam(required = false) GenreId genreId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            Pageable page) {
        var byTitle = PageRequest.of(page.getPageNumber(), page.getPageSize(), Sort.by("title"));
        return new PagedModel<>(service.findBooks(new BookSearch(title, author, authorId, genreId),
                new PriceRange(minPrice, maxPrice), byTitle));
    }

    @GetMapping("/{id}")
    StorefrontBook get(@PathVariable BookId id) {
        return service.findBook(id);
    }
}
