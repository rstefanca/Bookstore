package cz.codingmonkey.bookstore.bookcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import cz.codingmonkey.bookstore.TestAccountIds;
import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorService;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookService;
import cz.codingmonkey.bookstore.bookcatalog.internal.GenreService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

/**
 * Bootstraps only the book module (no audit module beans) against a Postgres container.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class BookModuleTests {

    @Autowired
    BookService service;

    @Autowired
    AuthorService authors;

    @Autowired
    GenreService genres;

    @Autowired
    EventSerializer serializer;

    @Test
    void eventsWithTypedIdsSurviveTheEventPublicationRegistry() {
        var event = new BookCreatedEvent(new BookId(7), "Good Omens", List.of("Terry Pratchett", "Neil Gaiman"),
                List.of("Comedy", "Fantasy"), "9780060853983", "Armageddon, with jokes.", 3, TestAccountIds.next());

        Object serialized = serializer.serialize(event);

        assertThat(serialized.toString()).contains("\"bookId\":7");
        assertThat(serializer.deserialize(serialized, BookCreatedEvent.class)).isEqualTo(event);
    }

    @Test
    void publishesBookCreatedEventWithAuthorNames(Scenario scenario) {
        var asimov = authors.create(new AuthorRequest("Isaac Asimov", Set.of())).id();
        var scienceFiction = genres.findAll("Science Fiction", Pageable.unpaged()).getContent().getFirst().id();
        var classics = genres.findAll("Classics", Pageable.unpaged()).getContent().getFirst().id();

        scenario.stimulate(() -> service.create(new BookRequest("Foundation", List.of(asimov),
                        Set.of(scienceFiction, classics), "9780553293357", 1951, null)))
                .andWaitForEventOfType(BookCreatedEvent.class)
                .matching(event -> event.isbn().equals("9780553293357"))
                .toArriveAndVerify((event, book) -> {
                    assertThat(event.bookId()).isEqualTo(book.id());
                    assertThat(event.title()).isEqualTo("Foundation");
                    assertThat(event.authors()).containsExactly("Isaac Asimov");
                    assertThat(event.genres()).as("sorted by name").containsExactly("Classics", "Science Fiction");
                });
    }

    @Test
    void publishesAuthorCreatedEvent(Scenario scenario) {
        scenario.stimulate(() -> authors.create(new AuthorRequest("Ursula K. Le Guin", Set.of())))
                .andWaitForEventOfType(AuthorCreatedEvent.class)
                .matching(event -> event.name().equals("Ursula K. Le Guin"))
                .toArriveAndVerify((event, author) -> {
                    assertThat(event.authorId()).isEqualTo(author.id());
                    assertThat(serializer.deserialize(serializer.serialize(event), AuthorCreatedEvent.class))
                            .isEqualTo(event);
                });
    }

    @Test
    void publishesBookUpdatedEventWithIncreasingRevision(Scenario scenario) {
        var herbert = authors.create(new AuthorRequest("Frank Herbert", Set.of())).id();
        var book = service.create(new BookRequest("Dune", List.of(herbert), Set.of(), "9780441013593", 1965, null));

        scenario.stimulate(() -> service.update(book.id(),
                        new BookRequest("Dune", List.of(herbert), Set.of(), "9780441013593", 1965, "Desert planet.")))
                .andWaitForEventOfType(BookUpdatedEvent.class)
                .matching(event -> event.bookId().equals(book.id()))
                .toArriveAndVerify(event -> {
                    assertThat(event.description()).isEqualTo("Desert planet.");
                    assertThat(event.authors()).containsExactly("Frank Herbert");
                    assertThat(event.revision()).as("created with revision 0").isEqualTo(1);
                });
    }

    @Test
    void publishesBookDeletedEvent(Scenario scenario) {
        var lem = authors.create(new AuthorRequest("Stanislaw Lem", Set.of())).id();
        var book = service.create(new BookRequest("Solaris", List.of(lem), Set.of(), "9780156027601", 1961, null));

        scenario.stimulate(() -> service.delete(book.id()))
                .andWaitForEventOfType(BookDeletedEvent.class)
                .matching(event -> event.bookId().equals(book.id()))
                .toArrive();
    }
}
