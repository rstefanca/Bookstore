package cz.codingmonkey.bookstore.recommendation;

import cz.codingmonkey.bookstore.bookcatalog.BookCreatedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookUpdatedEvent;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Keeps book embeddings in sync with the catalog. Runs asynchronously after the catalog transaction commits; events
 * are tracked in the event publication registry, so a failed embedding is retried rather than lost. Events may be
 * handled out of order, which the revision check in {@link BookEmbeddings#save} makes harmless.
 */
@Component
class BookEmbeddingUpdater {

    private static final Logger log = LogManager.getLogger(BookEmbeddingUpdater.class);
    private static final ChangeLog changes = ChangeLog.of(BookEmbeddingUpdater.class);

    private final EmbeddingModel model;
    private final BookEmbeddings embeddings;

    BookEmbeddingUpdater(EmbeddingModel model, BookEmbeddings embeddings) {
        this.model = model;
        this.embeddings = embeddings;
    }

    @ApplicationModuleListener
    void on(BookCreatedEvent event) {
        update(event.bookId(), event.revision(), event.title(), event.authors(), event.genres(), event.description());
    }

    @ApplicationModuleListener
    void on(BookUpdatedEvent event) {
        update(event.bookId(), event.revision(), event.title(), event.authors(), event.genres(), event.description());
    }

    @ApplicationModuleListener
    void on(BookDeletedEvent event) {
        embeddings.markDeleted(event.bookId());
        changes.info("Removed embedding of deleted book {}", event.bookId());
    }

    private void update(BookId bookId, long revision, String title, List<String> authors, List<String> genres,
            @Nullable String description) {
        float[] embedding = model.embed(textOf(title, authors, genres, description));
        if (embeddings.save(bookId, revision, title, authors, embedding)) {
            changes.info("Updated embedding of book {} (revision {})", bookId, revision);
        } else {
            log.debug("Ignored event for book {} (revision {}): outdated, repeated, or the book was deleted", bookId,
                    revision);
        }
    }

    /**
     * What the embedding represents: everything a reader would use to judge whether two books are alike, e.g.
     * "Science Fiction book. Dune by Frank Herbert. On the desert planet Arrakis... Genre: Science Fiction."
     * <p>
     * Plain sentences rather than labelled fields, and the genres at both ends: on a small evaluation set (9 books, 3
     * genres) this format gave the clearest separation between same-genre and other-genre books with
     * all-MiniLM-L6-v2. Changing it changes every embedding, so existing ones should be recomputed.
     */
    static String textOf(String title, List<String> authors, List<String> genres, @Nullable String description) {
        var text = new StringBuilder();
        String genreList = String.join(", ", genres);
        if (!genres.isEmpty()) {
            text.append(genreList).append(" book. ");
        }
        text.append(title).append(" by ").append(String.join(", ", authors)).append('.');
        if (description != null && !description.isBlank()) {
            text.append(' ').append(description.strip());
        }
        if (!genres.isEmpty()) {
            text.append(genres.size() == 1 ? " Genre: " : " Genres: ").append(genreList).append('.');
        }
        return text.toString();
    }
}
