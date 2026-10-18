package cz.codingmonkey.bookstore.recommendation;

import cz.codingmonkey.bookstore.shared.CurrentUser;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Recommendations")
class PersonalPicksController {

    private final PersonalPicksService picks;

    PersonalPicksController(PersonalPicksService picks) {
        this.picks = picks;
    }

    /**
     * Picks books for the signed-in customer from the books they rated 4 or 5 stars.
     * <p>
     * The picks are books similar to the liked ones, leaving out every book the customer has rated and every book
     * that isn't for sale. A customer who hasn't liked a book yet gets the top-rated books instead, marked with
     * {@code personal: false}.
     *
     * @param limit how many books to return at most
     */
    @GetMapping("/api/recommendations")
    @ApiResponse(responseCode = "200", description = "Picked books, best first; fewer than limit, or none, when there "
            + "aren't enough candidates")
    @ApiResponse(responseCode = "400", description = "limit isn't between 1 and 20")
    PersonalPicks picks(@RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit) {
        return picks.picksFor(CurrentUser.requireId(), limit);
    }
}
