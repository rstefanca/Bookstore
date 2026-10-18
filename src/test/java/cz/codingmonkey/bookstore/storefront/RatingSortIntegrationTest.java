package cz.codingmonkey.bookstore.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import cz.codingmonkey.bookstore.TestAccountIds;
import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorResponse;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * End to end: GET /api/books and the search_books MCP tool filter by rating ({@code minRating}) and sort by rating
 * ({@code orderBy=rating}). Ratings are inserted directly, so a test needs no customers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class RatingSortIntegrationTest {

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StorefrontMcpTools mcpTools;

    String titlePrefix;
    AuthorId author;

    /** Rated as in {@link #rateFiveBooks()}; D is unrated. */
    BookId a;
    BookId b;
    BookId c;
    BookId d;
    BookId e;

    @BeforeEach
    void setUp() {
        // Other tests leave ratings behind, which would shift the store-wide mean the rating order depends on.
        jdbc.update("DELETE FROM book_rating");
        titlePrefix = "Rated " + UUID.randomUUID().toString().substring(0, 8) + " ";
        author = client.post().uri("/api/authors")
                .body(new AuthorRequest("Rated Author", Set.of()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthorResponse.class).returnResult().getResponseBody().id();
    }

    @Test
    void filtersByMinimumRating() {
        rateFiveBooks();

        // A shows 4.8, B 5.0, C 4.7, E 1.0
        assertTitles("minRating=4", "A", "B", "C");
        assertTitles("minRating=4.8", "A", "B");
        assertTitles("minRating=4.9", "B");
        assertTitles("minRating=1", "A", "B", "C", "E");
        assertTitles("", "A", "B", "C", "D", "E");
    }

    @Test
    void rejectsMinRatingOutOfRange() {
        for (String value : List.of("0.9", "5.1", "0", "6")) {
            client.get().uri("/api/books?minRating=" + value).exchange()
                    .expectStatus().isBadRequest()
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.errors[0].field").isEqualTo("minRating");
        }
        client.get().uri("/api/books?minRating=good").exchange().expectStatus().isBadRequest();
    }

    @Test
    void sortsByRating() {
        rateFiveBooks();

        // m = 72 / 19 = 3.789: A 4.463, C 4.118, B 3.991, E 2.395, D unrated; title order would be A to E
        assertTitles("orderBy=rating", "A", "C", "B", "E", "D");
        assertTitles("orderBy=RATING", "A", "C", "B", "E", "D");
        assertTitles("orderBy=Title", "A", "B", "C", "D", "E");
        assertTitles("orderBy=rating&sort=title,desc", "A", "C", "B", "E", "D");
    }

    @Test
    void sortsByRatingAcrossPages() {
        rateFiveBooks();

        client.get().uri("/api/books?title={prefix}&orderBy=rating&page=1&size=2", titlePrefix)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.page.totalElements").isEqualTo(5)
                .jsonPath("$.content[*].title").value(found -> assertThat(found).asInstanceOf(LIST)
                        .containsExactly(titlePrefix + "B", titlePrefix + "E"));
    }

    @Test
    void sortsByTitleWhenNothingIsRated() {
        createBook("C");
        createBook("A");
        createBook("B");

        assertTitles("orderBy=rating", "A", "B", "C");
    }

    @Test
    void rejectsUnknownOrdering() {
        client.get().uri("/api/books?orderBy=price").exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("orderBy must be title or rating, was price");
    }

    @Test
    void combinesRatingAndPriceFilters() {
        rateFiveBooks();
        setPrice(a, "5");
        setPrice(b, "20");
        setPrice(c, "8");
        setPrice(e, "5");

        assertTitles("minRating=4&maxPrice=10", "A", "C");
        assertTitles("minRating=4&maxPrice=10&orderBy=rating", "A", "C");
        assertTitles("minRating=4&minPrice=10&orderBy=rating", "B");
        assertTitles("maxPrice=10&orderBy=rating", "A", "C", "E");
    }

    @Test
    void mcpSearchBooksFiltersAndSorts() {
        rateFiveBooks();

        assertThat(mcpTools.searchBooks(titlePrefix, null, null, null, null, null, new BigDecimal("4"), "rating", 10))
                .extracting(StorefrontBook::title)
                .containsExactly(titlePrefix + "A", titlePrefix + "C", titlePrefix + "B");
        assertThat(mcpTools.searchBooks(titlePrefix, null, null, null, null, null, null, null, 10))
                .extracting(StorefrontBook::title)
                .containsExactly(titlePrefix + "A", titlePrefix + "B", titlePrefix + "C", titlePrefix + "D",
                        titlePrefix + "E");
        assertThat(mcpTools.searchBooks(titlePrefix, null, null, null, null, null, new BigDecimal("4.8"), "Title", 10))
                .extracting(StorefrontBook::title)
                .containsExactly(titlePrefix + "A", titlePrefix + "B");
    }

    @Test
    void mcpSearchBooksRejectsInvalidArguments() {
        assertThatThrownBy(
                () -> mcpTools.searchBooks(null, null, null, null, null, null, new BigDecimal("5.1"), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("minRating must be between 1 and 5, was 5.1");
        assertThatThrownBy(() -> mcpTools.searchBooks(null, null, null, null, null, null, null, "price", null))
                .isInstanceOf(InvalidOrderingException.class)
                .hasMessage("orderBy must be title or rating, was price");
    }

    /**
     * A: eight 5 + two 4 (sum 48), B: one 5, C: 4, 5, 5 (sum 14), D: unrated, E: five 1. Title order is A to E,
     * plain average B, A, C, E, and the damped order is A, C, B, E.
     */
    private void rateFiveBooks() {
        a = createBook("A");
        b = createBook("B");
        c = createBook("C");
        d = createBook("D");
        e = createBook("E");
        rate(a, 5, 5, 5, 5, 5, 5, 5, 5, 4, 4);
        rate(b, 5);
        rate(c, 4, 5, 5);
        rate(e, 1, 1, 1, 1, 1);
    }

    private void assertTitles(String query, String... suffixes) {
        client.get().uri("/api/books?title={prefix}&" + query, titlePrefix)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.page.totalElements").isEqualTo(suffixes.length)
                .jsonPath("$.content[*].title").value(found -> assertThat(found).asInstanceOf(LIST)
                        .containsExactly(Arrays.stream(suffixes).map(s -> titlePrefix + s).toArray()));
    }

    private BookId createBook(String titleSuffix) {
        String isbn = "rsort-" + UUID.randomUUID().toString().substring(0, 8);
        return client.post().uri("/api/books")
                .body(new BookRequest(titlePrefix + titleSuffix, List.of(author), Set.of(), isbn, 2000, null))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookResponse.class).returnResult().getResponseBody().id();
    }

    private void setPrice(BookId book, String amount) {
        client.put().uri("/api/books/{id}/price", book)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": " + amount + "}")
                .exchange()
                .expectStatus().isOk();
    }

    /** One rating per given star value, by customers c1, c2, ... in turn. */
    private void rate(BookId book, int... stars) {
        for (int i = 0; i < stars.length; i++) {
            jdbc.update("INSERT INTO book_rating (book_id, customer_id, stars, rated_at) VALUES (?, ?, ?, ?)",
                    book.value(), TestAccountIds.next().value(), stars[i], OffsetDateTime.now());
        }
    }
}
