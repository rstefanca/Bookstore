package cz.codingmonkey.bookstore.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookCondition;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails.AuthorSummary;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookSearch;
import cz.codingmonkey.bookstore.pricing.Money;
import cz.codingmonkey.bookstore.pricing.Prices;
import cz.codingmonkey.bookstore.rating.RatingSummary;
import cz.codingmonkey.bookstore.rating.Ratings;
import cz.codingmonkey.bookstore.recommendation.Recommendation;
import cz.codingmonkey.bookstore.recommendation.Recommendations;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Bootstraps only the storefront; the catalog, pricing, rating and recommendation modules are mocked through their
 * public APIs.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class StorefrontModuleTests {

    static final BookId DUNE = new BookId(1);
    static final BookDetails DUNE_DETAILS = new BookDetails(DUNE, "Dune",
            List.of(new AuthorSummary(new AuthorId(1), "Frank Herbert")), List.of(), "9780441013593", 1965, null);

    @MockitoBean
    BookCatalog catalog;

    @MockitoBean
    Prices prices;

    @MockitoBean
    Ratings ratings;

    @MockitoBean
    Recommendations recommendations;

    @Autowired
    StorefrontService storefront;

    @BeforeEach
    void rateNothingByDefault() {
        // A mock would return null, which the non-null rating component doesn't allow.
        given(ratings.summaryOf(any())).willReturn(RatingSummary.NONE);
    }

    @Test
    void fetchesBookPriceAndRatingInParallel() {
        // Each query waits until the others have started too; run one after the other, the first would time out.
        var allStarted = new CyclicBarrier(3);
        var rating = RatingSummary.of(Map.of(4, 1));
        willAnswer(invocation -> {
            allStarted.await(5, TimeUnit.SECONDS);
            return rating;
        }).given(ratings).summaryOf(DUNE);
        willAnswer(invocation -> {
            allStarted.await(5, TimeUnit.SECONDS);
            return Optional.of(DUNE_DETAILS);
        }).given(catalog).findBook(DUNE);
        willAnswer(invocation -> {
            allStarted.await(5, TimeUnit.SECONDS);
            return Optional.of(Money.of("12.90", "EUR"));
        }).given(prices).priceOf(DUNE);

        StorefrontBook book = storefront.findBook(DUNE);

        assertThat(book.title()).isEqualTo("Dune");
        assertThat(book.price()).isEqualTo(Money.of("12.90", "EUR"));
        assertThat(book.rating()).isEqualTo(rating);
    }

    @Test
    void booksWithoutAPriceAreNotForSale() {
        given(catalog.findBook(DUNE)).willReturn(Optional.of(DUNE_DETAILS));
        given(prices.priceOf(DUNE)).willReturn(Optional.empty());

        assertThat(storefront.findBook(DUNE).price()).isNull();
    }

    @Test
    void unknownBooksAreNotFound() {
        given(catalog.findBook(DUNE)).willReturn(Optional.empty());
        given(prices.priceOf(DUNE)).willReturn(Optional.empty());

        assertThatThrownBy(() -> storefront.findBook(DUNE)).isInstanceOf(BookNotFoundException.class);
    }

    @Test
    void failuresSurfaceAsTheQuerysOwnException() {
        given(catalog.findBook(DUNE)).willReturn(Optional.of(DUNE_DETAILS));
        given(prices.priceOf(DUNE)).willThrow(new IllegalStateException("pricing is down"));

        assertThatThrownBy(() -> storefront.findBook(DUNE))
                .isInstanceOf(IllegalStateException.class).hasMessage("pricing is down");
    }

    @Test
    void pricesAPageOfBooksWithOneLookup() {
        var neuromancer = new BookDetails(new BookId(2), "Neuromancer", List.of(), List.of(), "9780441569595", 1984,
                null);
        var search = new BookSearch(null, null, null, null);
        given(catalog.findBooks(search, Pageable.ofSize(2))).willReturn(
                new PageImpl<>(List.of(DUNE_DETAILS, neuromancer), Pageable.ofSize(2), 7));
        given(prices.pricesOf(List.of(DUNE, new BookId(2)))).willReturn(Map.of(DUNE, Money.of("10", "EUR")));

        var page = storefront.findBooks(search, PriceRange.ANY, Pageable.ofSize(2));

        assertThat(page.getContent()).extracting(StorefrontBook::title, StorefrontBook::price)
                .containsExactly(
                        tuple("Dune", Money.of("10", "EUR")),
                        tuple("Neuromancer", null));
        assertThat(page.getTotalElements()).isEqualTo(7);
        verify(prices, never()).pricedBetween(any(), any());
    }

    @Test
    void listsBooksWithRatings() {
        var search = new BookSearch(null, null, null, null);
        given(catalog.findBooks(search, Pageable.ofSize(5)))
                .willReturn(new PageImpl<>(List.of(DUNE_DETAILS), Pageable.ofSize(5), 1));
        given(ratings.summariesOf(List.of(DUNE))).willReturn(Map.of(DUNE, RatingSummary.of(Map.of(5, 2))));

        var page = storefront.findBooks(search, PriceRange.ANY, Pageable.ofSize(5));

        assertThat(page.getContent()).singleElement().satisfies(book -> {
            assertThat(book.rating().average()).isEqualByComparingTo("5.0");
            assertThat(book.rating().count()).isEqualTo(2);
        });
    }

    @Test
    void limitsTheSearchToBooksInThePriceRange() {
        var search = new BookSearch("dune", null, null, null);
        BookCondition inRange = (bookId, query, cb) -> cb.conjunction();
        given(prices.pricedBetween(new BigDecimal("10"), new BigDecimal("15"))).willReturn(inRange);
        given(catalog.findBooks(search.matching(inRange), Pageable.ofSize(5)))
                .willReturn(new PageImpl<>(List.of(DUNE_DETAILS), Pageable.ofSize(5), 1));
        given(prices.pricesOf(List.of(DUNE))).willReturn(Map.of(DUNE, Money.of("14.99", "EUR")));

        var page = storefront.findBooks(search, new PriceRange(new BigDecimal("10"), new BigDecimal("15")),
                Pageable.ofSize(5));

        assertThat(page.getContent()).extracting(StorefrontBook::price).containsExactly(Money.of("14.99", "EUR"));
    }

    @Test
    void validatesPriceRanges() {
        assertThatThrownBy(() -> new PriceRange(new BigDecimal("-1"), null))
                .isInstanceOf(InvalidPriceRangeException.class).hasMessage("Prices can't be negative");
        assertThatThrownBy(() -> new PriceRange(new BigDecimal("20"), new BigDecimal("10")))
                .isInstanceOf(InvalidPriceRangeException.class)
                .hasMessage("minPrice (20) can't be above maxPrice (10)");
        assertThat(new PriceRange(new BigDecimal("10"), new BigDecimal("10")).isAny()).isFalse();
    }

    @Test
    void recommendsOnlyBooksForSaleInOrderOfSimilarity() {
        var neuromancer = new BookId(2);
        var unpriced = new BookId(3);
        var cookbook = new BookId(4);
        var neuromancerDetails = new BookDetails(neuromancer, "Neuromancer", List.of(), List.of(), "9780441569595",
                1984, null);
        var cookbookDetails = new BookDetails(cookbook, "The Joy of Cooking", List.of(), List.of(), "9780743246262",
                1931, null);
        given(recommendations.similarTo(DUNE, 2 * StorefrontService.CANDIDATES_PER_RESULT)).willReturn(Optional.of(
                List.of(new Recommendation(unpriced, 0.9), new Recommendation(neuromancer, 0.7),
                        new Recommendation(cookbook, 0.2))));
        given(prices.pricesOf(List.of(unpriced, neuromancer, cookbook)))
                .willReturn(Map.of(neuromancer, Money.of("9.99", "EUR"), cookbook, Money.of("25", "EUR")));
        // The catalog returns them in its own order.
        given(catalog.findBooks(any(), any()))
                .willReturn(new PageImpl<>(List.of(cookbookDetails, neuromancerDetails)));
        given(ratings.summariesOf(List.of(neuromancer, cookbook)))
                .willReturn(Map.of(neuromancer, RatingSummary.of(Map.of(5, 1))));

        List<SimilarBook> similar = storefront.findSimilar(DUNE, 2);

        assertThat(similar).extracting(SimilarBook::title, SimilarBook::price, SimilarBook::similarity)
                .containsExactly(
                        tuple("Neuromancer", Money.of("9.99", "EUR"), 0.7),
                        tuple("The Joy of Cooking", Money.of("25", "EUR"), 0.2));
        assertThat(similar.getFirst().rating().count()).isEqualTo(1);
        assertThat(similar.get(1).rating()).isEqualTo(RatingSummary.NONE);
    }

    @Test
    void stopsAtTheLimitAndSkipsBooksTheCatalogNoLongerHas() {
        var deleted = new BookId(2);
        var neuromancer = new BookId(3);
        var cookbook = new BookId(4);
        given(recommendations.similarTo(DUNE, StorefrontService.CANDIDATES_PER_RESULT)).willReturn(Optional.of(
                List.of(new Recommendation(deleted, 0.9), new Recommendation(neuromancer, 0.7),
                        new Recommendation(cookbook, 0.2))));
        given(prices.pricesOf(List.of(deleted, neuromancer, cookbook))).willReturn(Map.of(
                deleted, Money.of("1", "EUR"), neuromancer, Money.of("9.99", "EUR"), cookbook, Money.of("25", "EUR")));
        given(catalog.findBooks(any(), any())).willReturn(new PageImpl<>(List.of()));

        assertThat(storefront.findSimilar(DUNE, 1)).as("the one book for sale was just deleted").isEmpty();
    }

    @Test
    void doesNotQueryTheCatalogWhenNoRecommendationIsForSale() {
        given(recommendations.similarTo(DUNE, StorefrontService.CANDIDATES_PER_RESULT))
                .willReturn(Optional.of(List.of(new Recommendation(new BookId(2), 0.9))));
        given(prices.pricesOf(List.of(new BookId(2)))).willReturn(Map.of());

        assertThat(storefront.findSimilar(DUNE, 1)).isEmpty();
        verify(catalog, never()).findBooks(any(), any());
    }

    @Test
    void booksWithoutRecommendationsHaveNone() {
        given(recommendations.similarTo(any(), anyInt())).willReturn(Optional.empty());

        assertThatThrownBy(() -> storefront.findSimilar(DUNE, 5))
                .isInstanceOf(NoRecommendationsException.class)
                .hasMessageStartingWith("No recommendations for book 1 yet");
    }
}
