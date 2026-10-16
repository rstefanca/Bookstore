package cz.codingmonkey.bookstore.bookcatalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookSearch;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * With the {@code demo} profile, the empty catalog is filled at startup (in a context and database of its own).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration.class)
class DemoCatalogDataIntegrationTest {

    @Autowired
    BookRepository books;

    @Autowired
    AuthorRepository authors;

    @Autowired
    BookService bookService;

    @Autowired
    AuthorService authorService;

    @Autowired
    DemoCatalogData demoData;

    @Autowired
    RestTestClient client;

    @Test
    void loadsAllAuthorsAndBooks() {
        assertThat(authors.count()).isEqualTo(18);
        assertThat(books.count()).isEqualTo(26);
    }

    @Test
    void keepsCoAuthorsGenresAndFavorites() {
        BookResponse goodOmens = only("Good Omens");
        assertThat(goodOmens.authors()).extracting(BookResponse.AuthorSummary::name)
                .containsExactly("Terry Pratchett", "Neil Gaiman");
        assertThat(goodOmens.genres()).extracting(GenreResponse::name).containsExactly("Comedy", "Fantasy");
        assertThat(goodOmens.publishedYear()).isEqualTo(1990);
        assertThat(goodOmens.description()).startsWith("An angel and a demon");

        var asimov = authorService.findAll("Asimov", Pageable.unpaged()).getContent();
        assertThat(asimov).singleElement().satisfies(author -> assertThat(author.favoriteGenres())
                .extracting(GenreResponse::name).containsExactly("Science", "Science Fiction"));
    }

    @Test
    void loadsNothingOnceTheCatalogHasData() throws Exception {
        demoData.run(new DefaultApplicationArguments());

        assertThat(books.count()).isEqualTo(26);
        assertThat(authors.count()).isEqualTo(18);
    }

    @Test
    void demoBooksGetRecommendations() {
        BookId dune = only("Dune").id();

        // Embeddings are computed in the background after the books are created.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> client.get()
                .uri("/api/books/{id}/similar?limit=1", dune)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].title").value(title -> assertThat((String) title)
                        .isIn("Dune Messiah", "Children of Dune")));
    }

    @Test
    void demoBooksGetPricesAndStock() {
        BookId dune = only("Dune").id();
        BookId cosmos = only("Cosmos").id();
        BookId silmarillion = only("The Silmarillion").id();

        // Pricing and inventory react to the books' creation in the background.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            client.get().uri("/api/books/{id}", dune).exchange()
                    .expectBody().jsonPath("$.price.amount").isEqualTo(14.99);
            client.get().uri("/api/inventory/{id}", dune).exchange()
                    .expectBody().jsonPath("$.quantity").isEqualTo(12);
            client.get().uri("/api/books/{id}", cosmos).exchange()
                    .expectBody().jsonPath("$.price.amount").isEqualTo(21.00);
        });

        // Priced, but out of stock: never stocked.
        client.get().uri("/api/inventory/{id}", cosmos).exchange().expectStatus().isNotFound();
        // Neither priced nor stocked: in the catalog, but not for sale.
        client.get().uri("/api/books/{id}", silmarillion).exchange().expectBody().jsonPath("$.price").isEmpty();
        client.get().uri("/api/inventory/{id}", silmarillion).exchange().expectStatus().isNotFound();
    }

    private BookResponse only(String title) {
        List<BookResponse> found = bookService.findAll(new BookSearch(title, null, null, null), Pageable.unpaged())
                .getContent().stream().filter(book -> book.title().equals(title)).toList();
        assertThat(found).hasSize(1);
        return found.getFirst();
    }
}
