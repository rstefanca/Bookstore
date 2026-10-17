package cz.codingmonkey.bookstore.pricing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorResponse;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookResponse;
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
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class PricingControllerIntegrationTest {

    @Autowired
    RestTestClient client;

    AuthorId author;

    @BeforeEach
    void createAuthor() {
        author = client.post().uri("/api/authors")
                .body(new AuthorRequest("Priced Author", Set.of()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthorResponse.class).returnResult().getResponseBody().id();
    }

    @Test
    void newBooksHaveNoPriceUntilOneIsSet() {
        BookId book = createBook();

        client.get().uri("/api/books/{id}/price", book).exchange().expectStatus().isNotFound();

        setPrice(book, "{\"amount\": 12.9}")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.bookId").isEqualTo(book.value())
                .jsonPath("$.price.amount").isEqualTo(12.90)
                .jsonPath("$.price.currency").isEqualTo("EUR");

        client.get().uri("/api/books/{id}/price", book)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.price.amount").isEqualTo(12.90);
    }

    @Test
    void changesAndRemovesPrices() {
        BookId book = createBook();
        setPrice(book, "{\"amount\": 10, \"currency\": \"EUR\"}").expectStatus().isOk();

        setPrice(book, "{\"amount\": 8.50}").expectStatus().isOk()
                .expectBody().jsonPath("$.price.amount").isEqualTo(8.50);

        client.delete().uri("/api/books/{id}/price", book).exchange().expectStatus().isNoContent();
        client.get().uri("/api/books/{id}/price", book).exchange().expectStatus().isNotFound();
        client.delete().uri("/api/books/{id}/price", book).exchange().expectStatus().isNotFound();
    }

    @Test
    void exposesPriceHistory() {
        BookId book = createBook();
        client.get().uri("/api/books/{id}/price-history", book)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.content").isEmpty();

        setPrice(book, "{\"amount\": 10}").expectStatus().isOk();
        setPrice(book, "{\"amount\": 7.99}").expectStatus().isOk();

        client.get().uri("/api/books/{id}/price-history", book)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(2)
                .jsonPath("$.content[0].price.amount").isEqualTo(7.99)
                .jsonPath("$.content[0].price.currency").isEqualTo("EUR")
                .jsonPath("$.content[0].from").isNotEmpty()
                .jsonPath("$.content[0].until").isEmpty()
                .jsonPath("$.content[1].price.amount").isEqualTo(10.00)
                .jsonPath("$.content[1].until").isNotEmpty()
                .jsonPath("$.page.totalElements").isEqualTo(2);

        client.get().uri("/api/books/{id}/price-history?size=1&page=1", book)
                .exchange()
                .expectBody().jsonPath("$.content[0].price.amount").isEqualTo(10.00);
    }

    @Test
    void auditsPriceChanges() {
        BookId book = createBook();
        setPrice(book, "{\"amount\": 10}").expectStatus().isOk();
        setPrice(book, "{\"amount\": 8.50}").expectStatus().isOk();

        // The audit module records price changes asynchronously, after each transaction commits.
        await().untilAsserted(() -> client.get().uri("/api/audit?subjectType=BOOK&subjectId={id}", book)
                .exchange()
                .expectBody()
                .jsonPath("$.content[?(@.action == 'PRICE_CHANGED')].description").value(descriptions ->
                        assertThat(descriptions).asInstanceOf(LIST).containsExactly(
                                "Price changed from 10.00 EUR to 8.50 EUR", "Price set to 10.00 EUR")));
    }

    @Test
    void validatesPrices() {
        BookId book = createBook();

        setPrice(book, "{\"amount\": 0}").expectStatus().isBadRequest();
        setPrice(book, "{\"amount\": -5}").expectStatus().isBadRequest();
        setPrice(book, "{\"amount\": 9.999}").expectStatus().isBadRequest();
        setPrice(book, "{}").expectStatus().isBadRequest();
        setPrice(book, "{\"amount\": 10, \"currency\": \"USD\"}").expectStatus().isBadRequest();
        setPrice(book, "{\"amount\": 10, \"currency\": \"nope\"}").expectStatus().isBadRequest();
    }

    @Test
    void returns404ForBooksNotInTheCatalog() {
        setPrice(new BookId(999_999), "{\"amount\": 10}").expectStatus().isNotFound();
    }

    @Test
    void listsPricesForRequestedBooks() {
        BookId first = createBook();
        BookId second = createBook();
        BookId unpriced = createBook();
        setPrice(first, "{\"amount\": 10}").expectStatus().isOk();
        setPrice(second, "{\"amount\": 20}").expectStatus().isOk();

        client.get().uri("/api/prices?bookId={a}&bookId={b}&bookId={c}", first, second, unpriced)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(2)
                .jsonPath("$.content[0].bookId").isEqualTo(first.value())
                .jsonPath("$.content[1].price.amount").isEqualTo(20.00)
                .jsonPath("$.page.totalElements").isEqualTo(2);
    }

    @Test
    void deletingABookRemovesItsPrice() {
        BookId book = createBook();
        setPrice(book, "{\"amount\": 10}").expectStatus().isOk();

        client.delete().uri("/api/books/{id}", book).exchange().expectStatus().isNoContent();

        await().untilAsserted(() -> client.get().uri("/api/prices?bookId={id}", book)
                .exchange()
                .expectBody().jsonPath("$.content").isEmpty());
    }

    private RestTestClient.ResponseSpec setPrice(BookId book, String json) {
        return client.put().uri("/api/books/{id}/price", book)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange();
    }

    private BookId createBook() {
        String isbn = "price-" + UUID.randomUUID().toString().substring(0, 8);
        return client.post().uri("/api/books")
                .body(new BookRequest("Book " + isbn, List.of(author), Set.of(), isbn, 2000, null))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookResponse.class).returnResult().getResponseBody().id();
    }
}
