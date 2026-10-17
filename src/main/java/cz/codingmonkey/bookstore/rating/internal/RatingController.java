package cz.codingmonkey.bookstore.rating.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's rating of a book.
 */
@RestController
@RequestMapping("/api/books/{id}/rating")
@Tag(name = "Ratings", description = """
        Customers (`CUSTOMER`) rate books from 1 to 5 stars. Each customer has one rating per book; rating again \
        replaces it. Ratings can't be removed.""")
class RatingController {

    private final RatingService ratings;

    RatingController(RatingService ratings) {
        this.ratings = ratings;
    }

    /**
     * Rates a book with 1 to 5 stars, replacing your earlier rating of it.
     *
     * @param id the book
     */
    @PutMapping
    @ApiResponse(responseCode = "200", description = "Your rating of the book")
    @ApiResponse(responseCode = "400", description = "stars is missing or isn't a whole number from 1 to 5")
    @ApiResponse(responseCode = "404", description = "There is no book with this id")
    RatingResponse rate(@PathVariable BookId id, @Valid @RequestBody RatingRequest request, Principal customer) {
        return RatingResponse.of(ratings.rate(id, customer.getName(), request.stars()));
    }

    /**
     * Returns your rating of a book.
     *
     * @param id the book
     */
    @GetMapping
    @ApiResponse(responseCode = "200", description = "Your rating of the book")
    @ApiResponse(responseCode = "404", description = "You haven't rated this book, or there is no such book")
    RatingResponse find(@PathVariable BookId id, Principal customer) {
        return ratings.find(id, customer.getName()).map(RatingResponse::of).orElseThrow(RatingNotFoundException::new);
    }
}
