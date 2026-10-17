package cz.codingmonkey.bookstore.rating.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.rating.RatingSummary;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Only the rating module runs; the catalog is mocked.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class RatingModuleTests {

    static final BookId DUNE = new BookId(1);
    static final BookId NEUROMANCER = new BookId(2);

    @MockitoBean
    BookCatalog catalog;

    @Autowired
    RatingService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM book_rating");
        given(catalog.existsAndLockAgainstDeletion(any())).willReturn(true);
    }

    @Test
    void summarizesRatings() {
        service.rate(DUNE, "a", 4);
        service.rate(DUNE, "b", 4);
        service.rate(DUNE, "c", 5);

        RatingSummary summary = service.summaryOf(DUNE);

        assertThat(summary.average()).isEqualTo(new BigDecimal("4.3"));
        assertThat(summary.count()).isEqualTo(3);
        assertThat(summary.stars()).containsExactly(
                Map.entry(1, 0), Map.entry(2, 0), Map.entry(3, 0),
                Map.entry(4, 2), Map.entry(5, 1));
    }

    @Test
    void roundsHalfUp() {
        assertThat(averageOf(4, 4, 4, 5)).isEqualTo(new BigDecimal("4.3"));
        assertThat(averageOf(1, 2)).isEqualTo(new BigDecimal("1.5"));
        assertThat(averageOf(4, 5, 5, 5, 5, 5)).isEqualTo(new BigDecimal("4.8"));
        assertThat(averageOf(1, 1, 2)).isEqualTo(new BigDecimal("1.3"));
        assertThat(averageOf(4, 5, 5)).isEqualTo(new BigDecimal("4.7"));
    }

    @Test
    void ratingAgainReplacesTheStars() {
        service.rate(DUNE, "a", 2);
        service.rate(DUNE, "a", 5);

        RatingSummary summary = service.summaryOf(DUNE);
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.average()).isEqualTo(new BigDecimal("5.0"));
        assertThat(service.find(DUNE, "a")).hasValueSatisfying(rating -> assertThat(rating.stars()).isEqualTo(5));
    }

    @Test
    void unratedBooksHaveNoAverage() {
        RatingSummary summary = service.summaryOf(DUNE);

        assertThat(summary).isEqualTo(RatingSummary.NONE);
        assertThat(summary.average()).isNull();
        assertThat(summary.count()).isZero();
        assertThat(summary.stars().keySet()).containsExactly(1, 2, 3, 4, 5);
        assertThat(summary.stars().values()).containsOnly(0);
    }

    @Test
    void summariesCoverEveryRequestedBook() {
        service.rate(DUNE, "a", 3);

        var summaries = service.summariesOf(List.of(DUNE, NEUROMANCER));

        assertThat(summaries).containsOnlyKeys(DUNE, NEUROMANCER);
        assertThat(summaries.get(DUNE).count()).isEqualTo(1);
        assertThat(summaries.get(NEUROMANCER)).isEqualTo(RatingSummary.NONE);
    }

    @Test
    void rejectsUnknownBooks() {
        given(catalog.existsAndLockAgainstDeletion(DUNE)).willReturn(false);

        assertThatThrownBy(() -> service.rate(DUNE, "a", 4)).isInstanceOf(UnknownBookException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM book_rating", Integer.class)).isZero();
    }

    @Test
    void bookDeletionRemovesItsRatings(Scenario scenario) {
        service.rate(DUNE, "a", 4);
        service.rate(NEUROMANCER, "a", 5);
        BookDeletedEvent event = new BookDeletedEvent(DUNE, "9780441013593", "staff");

        scenario.publish(event)
                .andWaitForStateChange(() -> service.summaryOf(DUNE).count(), count -> count == 0);

        assertThat(service.summaryOf(NEUROMANCER).count()).isEqualTo(1);

        // Redelivery is harmless.
        scenario.publish(event).andWaitAtMost(Duration.ofSeconds(1))
                .andWaitForStateChange(() -> service.summaryOf(NEUROMANCER).count(), count -> count == 1)
                .andVerify(count -> assertThat(count).isEqualTo(1));
        assertThat(service.summaryOf(DUNE).count()).isZero();
    }

    private BigDecimal averageOf(int... stars) {
        jdbc.update("DELETE FROM book_rating");
        for (int i = 0; i < stars.length; i++) {
            service.rate(DUNE, "customer" + i, stars[i]);
        }
        return service.summaryOf(DUNE).average();
    }
}
