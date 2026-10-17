package cz.codingmonkey.bookstore.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.BookCreatedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookDeletedEvent;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookUpdatedEvent;
import cz.codingmonkey.bookstore.pricing.Prices;
import cz.codingmonkey.bookstore.rating.Ratings;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Bootstraps only the recommendation module, with the real embedding model, and drives it through book events.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class RecommendationModuleTests {

    static final BookId DUNE = new BookId(1);
    static final BookId CHILDREN_OF_DUNE = new BookId(2);
    static final BookId NEUROMANCER = new BookId(3);
    static final BookId COOKBOOK = new BookId(4);

    @Autowired
    BookEmbeddings embeddings;

    @Autowired
    JdbcTemplate jdbc;

    /** Used by personal picks, which these tests don't cover; other modules aren't started. */
    @MockitoBean
    Ratings ratings;

    @MockitoBean
    Prices prices;

    @BeforeEach
    void cleanEmbeddings() {
        jdbc.update("DELETE FROM book_embedding");
    }

    @Test
    void recommendsSemanticallySimilarBooksFirst(Scenario scenario) {
        publishCreated(scenario, DUNE, "Dune", "Frank Herbert",
                "On the desert planet Arrakis, Paul Atreides fights for control of the spice melange.");
        publishCreated(scenario, CHILDREN_OF_DUNE, "Children of Dune", "Frank Herbert",
                "Paul's twin children inherit the desert world of Arrakis and its spice-fuelled empire.");
        publishCreated(scenario, NEUROMANCER, "Neuromancer", "William Gibson",
                "A washed-up hacker is hired for one last job in cyberspace.");
        publishCreated(scenario, COOKBOOK, "The Joy of Cooking", "Irma S. Rombauer",
                "Classic American recipes for soups, breads, roasts and desserts.");

        List<SimilarBook> similar = embeddings.findSimilar(DUNE, 5);

        assertThat(similar).extracting(SimilarBook::title)
                .as("every other book, never the book itself")
                .containsExactlyInAnyOrder("Children of Dune", "Neuromancer", "The Joy of Cooking");
        assertThat(similar).extracting(SimilarBook::similarity).isSortedAccordingTo((a, b) -> Double.compare(b, a));
        // The sequel shares the topic; the others don't, so it should stand out clearly (about 0.8 vs. 0.2-0.3).
        assertThat(similar.getFirst().title()).isEqualTo("Children of Dune");
        assertThat(similar.getFirst().similarity()).isGreaterThan(0.6);
        assertThat(similar.subList(1, 3)).allSatisfy(book -> assertThat(book.similarity()).isLessThan(0.5));
        assertThat(similar.getFirst().authors()).containsExactly("Frank Herbert");
        assertThat(embeddings.findSimilar(DUNE, 1)).hasSize(1);
    }

    @Test
    void updateRecomputesTheEmbedding(Scenario scenario) {
        publishCreated(scenario, DUNE, "Dune", "Frank Herbert", "Desert planet, spice, prophecy.");

        scenario.publish(new BookUpdatedEvent(DUNE, "Dune (Deluxe Edition)", List.of("Frank Herbert"), List.of(),
                        "9780441013593", "Desert planet, spice, prophecy. Illustrated.", 1))
                .andWaitForStateChange(() -> titleOf(DUNE), "Dune (Deluxe Edition)"::equals)
                .andVerify(title -> assertThat(revisionOf(DUNE)).isEqualTo(1));
    }

    @Test
    void ignoresOutOfOrderEvents() {
        assertThat(embeddings.save(DUNE, 2, "Dune, revision 2", List.of("Frank Herbert"), vector(1))).isTrue();

        assertThat(embeddings.save(DUNE, 1, "Dune, revision 1", List.of("Frank Herbert"), vector(2)))
                .as("older revision arriving late").isFalse();
        assertThat(embeddings.save(DUNE, 2, "Dune, revision 2 again", List.of("Frank Herbert"), vector(2)))
                .as("same revision delivered twice").isFalse();
        assertThat(titleOf(DUNE)).isEqualTo("Dune, revision 2");
    }

    @Test
    void deletedBooksStayDeletedAndAreNeverRecommended(Scenario scenario) {
        publishCreated(scenario, DUNE, "Dune", "Frank Herbert", "Desert planet, spice, prophecy.");
        publishCreated(scenario, CHILDREN_OF_DUNE, "Children of Dune", "Frank Herbert", "The desert planet again.");

        scenario.publish(new BookDeletedEvent(CHILDREN_OF_DUNE, "9780441104024", null))
                .andWaitForStateChange(() -> embeddings.exists(CHILDREN_OF_DUNE), exists -> !exists)
                .andVerify(exists -> assertThat(embeddings.findSimilar(DUNE, 5)).isEmpty());

        assertThat(embeddings.save(CHILDREN_OF_DUNE, 5, "Children of Dune", List.of("Frank Herbert"), vector(1)))
                .as("late event for a deleted book").isFalse();
        assertThat(embeddings.exists(CHILDREN_OF_DUNE)).isFalse();
    }

    @Test
    void sharedGenresRaiseSimilarity(Scenario scenario) {
        String neuromancer = "A washed-up hacker is hired for one last job in cyberspace.";
        publishCreated(scenario, DUNE, "Dune", "Frank Herbert", List.of("Science Fiction"),
                "On the desert planet Arrakis, Paul Atreides fights for control of the spice melange.");
        publishCreated(scenario, NEUROMANCER, "Neuromancer", "William Gibson", List.of("Science Fiction"), neuromancer);
        // The same book without a genre, for comparison.
        publishCreated(scenario, new BookId(5), "Neuromancer", "William Gibson", List.of(), neuromancer);

        List<SimilarBook> similar = embeddings.findSimilar(DUNE, 5);

        assertThat(similar.getFirst().bookId()).as("the same-genre copy ranks first").isEqualTo(NEUROMANCER);
        assertThat(similar.get(0).similarity() - similar.get(1).similarity()).isGreaterThan(0.05);
    }

    @Test
    void embeddingTextCombinesGenresTitleAuthorsAndDescription() {
        assertThat(BookEmbeddingUpdater.textOf("Good Omens", List.of("Terry Pratchett", "Neil Gaiman"),
                List.of("Comedy", "Fantasy"), "Armageddon, with jokes."))
                .isEqualTo("Comedy, Fantasy book. Good Omens by Terry Pratchett, Neil Gaiman. Armageddon, with jokes."
                        + " Genres: Comedy, Fantasy.");
        assertThat(BookEmbeddingUpdater.textOf("Dune", List.of("Frank Herbert"), List.of("Science Fiction"), " "))
                .isEqualTo("Science Fiction book. Dune by Frank Herbert. Genre: Science Fiction.");
        assertThat(BookEmbeddingUpdater.textOf("Dune", List.of("Frank Herbert"), List.of(), null))
                .isEqualTo("Dune by Frank Herbert.");
    }

    private void publishCreated(Scenario scenario, BookId id, String title, String author, String description) {
        publishCreated(scenario, id, title, author, List.of(), description);
    }

    private void publishCreated(Scenario scenario, BookId id, String title, String author, List<String> genres,
            String description) {
        scenario.publish(new BookCreatedEvent(id, title, List.of(author), genres, "isbn-" + id, description, 0, null))
                .andWaitForStateChange(() -> embeddings.exists(id), Boolean::booleanValue)
                .andVerify(exists -> assertThat(exists).isTrue());
    }

    private String titleOf(BookId id) {
        return jdbc.queryForObject("SELECT title FROM book_embedding WHERE book_id = ?", String.class, id.value());
    }

    private long revisionOf(BookId id) {
        return jdbc.queryForObject("SELECT revision FROM book_embedding WHERE book_id = ?", Long.class, id.value());
    }

    private static float[] vector(float value) {
        float[] vector = new float[BookEmbeddings.DIMENSIONS];
        Arrays.fill(vector, value);
        return vector;
    }
}
