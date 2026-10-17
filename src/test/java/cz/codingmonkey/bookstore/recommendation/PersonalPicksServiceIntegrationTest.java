package cz.codingmonkey.bookstore.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorResponse;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookResponse;
import cz.codingmonkey.bookstore.pricing.internal.PriceRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Personal picks from the books a customer liked, and the top-rated fallback, over real embeddings.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class PersonalPicksServiceIntegrationTest {

    static final String CUSTOMER = "picky-customer";

    @Autowired
    PersonalPicksService picks;

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    BookId dune;
    BookId childrenOfDune;
    BookId duneMessiah;
    BookId cookbook;

    @BeforeEach
    void setUp() {
        // Other test classes leave ratings behind, which would shift the store-wide mean and the top-rated list.
        // Deleting books directly bypasses the book events, so the embeddings have to be cleared as well.
        jdbc.update("DELETE FROM book_rating");
        jdbc.update("DELETE FROM book");
        jdbc.update("DELETE FROM book_embedding");

        AuthorId herbert = createAuthor("Frank Herbert");
        dune = createBook("Dune", herbert, "9780441013593",
                "On the desert planet Arrakis, Paul Atreides fights for control of the spice melange.");
        childrenOfDune = createBook("Children of Dune", herbert, "9780441104024",
                "Paul's twin children inherit the desert world of Arrakis and its spice-fuelled empire.");
        duneMessiah = createBook("Dune Messiah", herbert, "9780441172696",
                "Emperor Paul Atreides rules from Arrakis as his jihad spreads the spice empire across the stars.");
        cookbook = createBook("The Joy of Cooking", createAuthor("Irma S. Rombauer"), "9780743246262",
                "Classic American recipes for soups, breads, roasts and desserts.");

        // Embeddings are computed asynchronously after each book is created.
        List<BookId> own = List.of(dune, childrenOfDune, duneMessiah, cookbook);
        await().until(() -> own.stream().allMatch(book -> jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM book_embedding WHERE book_id = ? AND NOT deleted)",
                Boolean.class, book.value())));

        // Books other test classes created may still get their embedding after the wipe above; they'd become
        // candidates, so tombstone every embedding that isn't one of ours (as a book deletion would).
        jdbc.update("""
                UPDATE book_embedding SET deleted = true, title = NULL, authors = NULL, embedding = NULL
                WHERE book_id NOT IN (?, ?, ?, ?)
                """, dune.value(), childrenOfDune.value(), duneMessiah.value(), cookbook.value());
    }

    @Test
    void likedBookPullsSimilarBooksFirst() {
        priceAll();
        rate(dune, CUSTOMER, 5);

        PersonalPicks result = picks.picksFor(CUSTOMER, 10);

        assertThat(result.personal()).isTrue();
        assertThat(ids(result)).containsExactlyInAnyOrder(childrenOfDune, duneMessiah, cookbook);
        assertThat(ids(result).getFirst()).isIn(childrenOfDune, duneMessiah);
        assertThat(ids(result).getLast()).as("cookbook after both sequels").isEqualTo(cookbook);
        PickedBook first = result.books().getFirst();
        assertThat(first.title()).isIn("Children of Dune", "Dune Messiah");
        assertThat(first.authors()).containsExactly("Frank Herbert");
    }

    @Test
    void leavesOutRatedAndUnpricedBooks() {
        setPrice(dune);
        setPrice(duneMessiah);
        setPrice(cookbook);
        rate(dune, CUSTOMER, 5);
        rate(duneMessiah, CUSTOMER, 2);

        PersonalPicks result = picks.picksFor(CUSTOMER, 10);

        assertThat(result.personal()).isTrue();
        assertThat(ids(result)).containsExactly(cookbook);
    }

    @Test
    void fillsFromLaterBatches() {
        // limit 1 reads batches of 2: the first holds both sequels, neither for sale.
        setPrice(dune);
        setPrice(cookbook);
        rate(dune, CUSTOMER, 5);

        PersonalPicks result = picks.picksFor(CUSTOMER, 1);

        assertThat(result.personal()).isTrue();
        assertThat(ids(result)).containsExactly(cookbook);
    }

    @Test
    void stopsWhenCandidatesRunOut() {
        priceAll();
        rate(dune, CUSTOMER, 5);
        rate(childrenOfDune, CUSTOMER, 3);
        rate(duneMessiah, CUSTOMER, 1);

        PersonalPicks result = picks.picksFor(CUSTOMER, 20);

        assertThat(result.personal()).isTrue();
        assertThat(ids(result)).containsExactly(cookbook);
    }

    @Test
    void fallsBackToTopRatedWithoutLikes() {
        priceAll();
        rate(cookbook, CUSTOMER, 2);
        rate(duneMessiah, "other-1", 5);
        rate(childrenOfDune, "other-2", 4);

        PersonalPicks result = picks.picksFor(CUSTOMER, 10);

        assertThat(result.personal()).isFalse();
        assertThat(ids(result)).containsExactly(duneMessiah, childrenOfDune);
        assertThat(result.books().getFirst().title()).isEqualTo("Dune Messiah");
    }

    @Test
    void fallsBackWhenLikedBooksHaveNoEmbedding() {
        priceAll();
        rate(dune, CUSTOMER, 5);
        rate(childrenOfDune, "other-1", 4);
        jdbc.update("DELETE FROM book_embedding WHERE book_id = ?", dune.value());

        PersonalPicks result = picks.picksFor(CUSTOMER, 10);

        assertThat(result.personal()).isFalse();
        assertThat(ids(result)).containsExactly(childrenOfDune);
    }

    @Test
    void emptyWithoutAnyRatings() {
        priceAll();

        PersonalPicks result = picks.picksFor("fresh-customer", 10);

        assertThat(result.personal()).isFalse();
        assertThat(result.books()).isEmpty();
    }

    private static List<BookId> ids(PersonalPicks picks) {
        return picks.books().stream().map(PickedBook::bookId).toList();
    }

    private void priceAll() {
        for (BookId book : List.of(dune, childrenOfDune, duneMessiah, cookbook)) {
            setPrice(book);
        }
    }

    private void setPrice(BookId book) {
        client.put().uri("/api/books/{id}/price", book)
                .body(new PriceRequest(new BigDecimal("12.90"), null))
                .exchange()
                .expectStatus().isOk();
    }

    private void rate(BookId book, String customer, int stars) {
        jdbc.update("INSERT INTO book_rating (book_id, customer, stars, rated_at) VALUES (?, ?, ?, ?)",
                book.value(), customer, stars, OffsetDateTime.now());
    }

    private AuthorId createAuthor(String name) {
        return client.post().uri("/api/authors")
                .body(new AuthorRequest(name, Set.of()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthorResponse.class).returnResult().getResponseBody().id();
    }

    private BookId createBook(String title, AuthorId author, String isbn, String description) {
        return client.post().uri("/api/books")
                .body(new BookRequest(title, List.of(author), Set.of(), isbn, 2000, description))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookResponse.class).returnResult().getResponseBody().id();
    }
}
