package cz.codingmonkey.bookstore.rating.internal;

import static org.assertj.core.api.Assertions.assertThat;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookSearch;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorService;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookService;
import cz.codingmonkey.bookstore.rating.Ratings;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The rating filter and order applied by the catalog, and the reads the recommendation module uses.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RatingRankingIntegrationTest {

    @Autowired
    Ratings ratings;

    @Autowired
    BookCatalog catalog;

    @Autowired
    BookService books;

    @Autowired
    AuthorService authors;

    @Autowired
    JdbcTemplate jdbc;

    /** Unique per test, so searches by title only see this test's books. */
    String marker;
    AuthorId author;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM book_rating");
        marker = "Ranked " + UUID.randomUUID().toString().substring(0, 8);
        author = authors.create(new AuthorRequest("Ranked Author", Set.of())).id();
    }

    @Test
    void ratedAtLeastUsesTheDisplayedAverage() {
        BookId x = createBook("X");
        BookId y = createBook("Y");
        createBook("Z");
        rate(x, repeat(4, 19), 5); // 81 / 20 = 4.05, shown 4.1
        rate(y, repeat(4, 19), 3); // 79 / 20 = 3.95, shown 4.0

        assertThat(ratedAtLeast("4.1")).containsExactly(x);
        assertThat(ratedAtLeast("4")).containsExactly(x, y);
        assertThat(ratedAtLeast("1")).as("unrated never match").containsExactly(x, y);
    }

    @Test
    void byRatingRanksTheDampedAverage() {
        List<BookId> abcde = rateFiveBooks();

        // m = 72 / 19 = 3.789: A 4.463, C 4.118, B 3.991, E 2.395, D unrated
        assertThat(byRating()).containsExactly(abcde.get(0), abcde.get(2), abcde.get(1), abcde.get(4),
                abcde.get(3));
    }

    @Test
    void byRatingWithoutAnyRatingsIsTheTitleOrder() {
        BookId c = createBook("C");
        BookId a = createBook("A");
        BookId b = createBook("B");

        assertThat(byRating()).containsExactly(a, b, c);
    }

    @Test
    void ratingsByReturnsTheCustomersStars() {
        BookId x = createBook("X");
        BookId y = createBook("Y");
        insertRating(x, "c1", 5);
        insertRating(y, "c1", 2);
        insertRating(x, "c2", 3);

        assertThat(ratings.ratingsBy("c1")).isEqualTo(Map.of(x, 5, y, 2));
        assertThat(ratings.ratingsBy("unknown")).isEmpty();
    }

    @Test
    void topRatedWalksRatedBooksBestFirst() {
        List<BookId> abcde = rateFiveBooks();

        assertThat(ratings.topRated(0, 2)).containsExactly(abcde.get(0), abcde.get(2));
        assertThat(ratings.topRated(2, 2)).containsExactly(abcde.get(1), abcde.get(4));
        assertThat(ratings.topRated(4, 2)).isEmpty();
    }

    /**
     * A: eight 5 + two 4 (sum 48), B: one 5, C: 4, 5, 5 (sum 14), D: unrated, E: five 1. Title order is A to E,
     * plain average B, A, C, E, and integer division would give A, B, C, E: all differ from the damped order.
     */
    private List<BookId> rateFiveBooks() {
        BookId a = createBook("A");
        BookId b = createBook("B");
        BookId c = createBook("C");
        BookId d = createBook("D");
        BookId e = createBook("E");
        rate(a, 5, 5, 5, 5, 5, 5, 5, 5, 4, 4);
        rate(b, 5);
        rate(c, 4, 5, 5);
        rate(e, 1, 1, 1, 1, 1);
        return List.of(a, b, c, d, e);
    }

    private List<BookId> ratedAtLeast(String min) {
        return find(new BookSearch(marker, null, null, null).matching(ratings.ratedAtLeast(new BigDecimal(min))),
                PageRequest.of(0, 10, Sort.by("title")));
    }

    private List<BookId> byRating() {
        return find(new BookSearch(marker, null, null, null).ordered(ratings.byRating()), Pageable.unpaged());
    }

    private List<BookId> find(BookSearch search, Pageable page) {
        return catalog.findBooks(search, page).getContent().stream().map(BookDetails::id).toList();
    }

    private BookId createBook(String name) {
        String isbn = "rank-" + UUID.randomUUID().toString().substring(0, 12);
        return books.create(new BookRequest(marker + " " + name, List.of(author), Set.of(), isbn, 2000, null)).id();
    }

    /** One rating per given star value, by customers c1, c2, ... in turn. */
    private void rate(BookId book, int... stars) {
        for (int i = 0; i < stars.length; i++) {
            insertRating(book, "c" + (i + 1), stars[i]);
        }
    }

    private void rate(BookId book, int[] first, int last) {
        int[] all = Arrays.copyOf(first, first.length + 1);
        all[first.length] = last;
        rate(book, all);
    }

    private static int[] repeat(int stars, int times) {
        int[] all = new int[times];
        Arrays.fill(all, stars);
        return all;
    }

    private void insertRating(BookId book, String customer, int stars) {
        jdbc.update("INSERT INTO book_rating (book_id, customer, stars, rated_at) VALUES (?, ?, ?, ?)",
                book.value(), customer, stars, OffsetDateTime.now());
    }
}
