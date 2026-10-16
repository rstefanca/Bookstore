package cz.codingmonkey.bookstore.recommendation;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
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
class RecommendationController {

    private final BookEmbeddings embeddings;

    RecommendationController(BookEmbeddings embeddings) {
        this.embeddings = embeddings;
    }

    /**
     * Books similar to the given one, most similar first. Returns 404 for books the recommendation module doesn't
     * know (yet): embeddings are computed asynchronously, shortly after a book is created.
     */
    @GetMapping("/api/books/{bookId}/similar")
    List<SimilarBook> similar(@PathVariable BookId bookId,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int limit) {
        if (!embeddings.exists(bookId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No recommendations for book " + bookId + " yet");
        }
        return embeddings.findSimilar(bookId, limit);
    }
}
