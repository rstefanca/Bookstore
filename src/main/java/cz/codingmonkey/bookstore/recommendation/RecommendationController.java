package cz.codingmonkey.bookstore.recommendation;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Tag(name = "Recommendations", description = """
        Books similar to a given one, judged by their title, authors, genres and description (text embeddings). \
        Open to anyone.""")
class RecommendationController {

    private final BookEmbeddings embeddings;

    RecommendationController(BookEmbeddings embeddings) {
        this.embeddings = embeddings;
    }

    /**
     * Lists books similar to a book, most similar first.
     * <p>
     * Recommendations are computed in the background, shortly after a book is created or changed; until a new
     * book's are ready, this answers 404.
     *
     * @param limit how many books to return
     */
    @GetMapping("/api/books/{bookId}/similar")
    @SecurityRequirements
    @ApiResponse(responseCode = "200", description = "Similar books, most similar first")
    @ApiResponse(responseCode = "400", description = "limit isn't between 1 and 20")
    @ApiResponse(responseCode = "404", description = "Unknown book, or its recommendations aren't ready yet")
    List<SimilarBook> similar(@PathVariable BookId bookId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int limit) {
        if (!embeddings.exists(bookId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No recommendations for book " + bookId + " yet");
        }
        return embeddings.findSimilar(bookId, limit);
    }
}
