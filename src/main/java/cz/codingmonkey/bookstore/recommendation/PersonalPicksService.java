package cz.codingmonkey.bookstore.recommendation;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.pricing.Prices;
import cz.codingmonkey.bookstore.rating.Ratings;
import cz.codingmonkey.bookstore.shared.AccountId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Picks books for a customer: books for sale they haven't rated, nearest to the average embedding of the books they
 * liked; or, until they like a book, the store's top-rated books.
 */
@Service
class PersonalPicksService {

    /** Stars from which a rating counts as liking the book. */
    static final int LIKED_FROM_STARS = 4;

    private final Ratings ratings;
    private final Prices prices;
    private final BookEmbeddings embeddings;

    PersonalPicksService(Ratings ratings, Prices prices, BookEmbeddings embeddings) {
        this.ratings = ratings;
        this.prices = prices;
        this.embeddings = embeddings;
    }

    /**
     * Up to {@code limit} books for the customer, best first.
     *
     * @param customer the customer's account
     */
    PersonalPicks picksFor(AccountId customer, int limit) {
        Map<BookId, Integer> stars = ratings.ratingsBy(customer);
        Set<BookId> excluded = stars.keySet();
        List<BookId> liked = stars.entrySet().stream()
                .filter(rating -> rating.getValue() >= LIKED_FROM_STARS)
                .map(Map.Entry::getKey)
                .toList();

        // A just-created liked book may not have its embedding yet; with none of them there's no taste vector.
        if (!liked.isEmpty() && embeddings.anyExists(liked)) {
            return new PersonalPicks(true, fill(limit, (offset, count) -> {
                List<PickedBook> nearest = embeddings.nearestTo(liked, excluded, offset, count);
                return new Batch(nearest.size(), nearest);
            }));
        }

        List<PickedBook> pickedBooks = fill(limit, (offset, count) -> {
            List<BookId> topRated = ratings.topRated(offset, count);
            List<BookId> candidates = topRated.stream().filter(id -> !excluded.contains(id)).toList();
            return new Batch(topRated.size(), candidates.isEmpty() ? List.of() : embeddings.findLive(candidates));
        });
        return new PersonalPicks(false, pickedBooks);
    }

    /**
     * Reads candidates from the source in batches of {@code 2 × limit} and keeps those for sale, until {@code limit}
     * are kept or a batch comes back short (the source ran out).
     */
    private List<PickedBook> fill(int limit, CandidateSource source) {
        int batchSize = 2 * limit;
        List<PickedBook> kept = new ArrayList<>(limit);
        // Every batch re-reads the live data at a new offset, so rows shifting between batches can repeat a book.
        Set<BookId> keptIds = new HashSet<>();
        int offset = 0;
        while (true) {
            Batch batch = source.read(offset, batchSize);
            offset += batch.read();
            if (!batch.candidates().isEmpty()) {
                Set<BookId> forSale = prices.pricesOf(batch.candidates().stream().map(PickedBook::bookId).toList())
                        .keySet();
                for (PickedBook candidate : batch.candidates()) {
                    if (kept.size() == limit) {
                        break;
                    }
                    if (forSale.contains(candidate.bookId()) && keptIds.add(candidate.bookId())) {
                        kept.add(candidate);
                    }
                }
            }
            if (kept.size() == limit || batch.read() < batchSize) {
                return kept;
            }
        }
    }

    /** Candidates in rank order, a window of {@code count} starting at {@code offset}. */
    @FunctionalInterface
    private interface CandidateSource {

        Batch read(int offset, int count);
    }

    /**
     * One window read from a candidate source.
     *
     * @param read       how many entries the source returned, before any were dropped; fewer than requested means
     *                   the source has run out
     * @param candidates the entries that may be picked if for sale, in rank order
     */
    private record Batch(int read, List<PickedBook> candidates) {
    }
}
