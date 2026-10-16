package cz.codingmonkey.bookstore.inventory.internal;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class InventoryControllerIntegrationTest {

    @Autowired
    RestTestClient client;

    @Test
    void newBookHasNoStockUntilFirstRestock() {
        BookId bookId = createBook("9780553418026");

        client.get().uri("/api/inventory/{id}", bookId).exchange().expectStatus().isNotFound();

        restock(bookId, 10).expectStatus().isOk().expectBody().jsonPath("$.quantity").isEqualTo(10);
        withdraw(bookId, 4).expectStatus().isOk().expectBody().jsonPath("$.quantity").isEqualTo(6);

        client.get().uri("/api/inventory/{id}", bookId)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.quantity").isEqualTo(6);

        // Book creation plus both stock changes show up in the audit module.
        await().untilAsserted(() -> client.get().uri("/api/audit?bookId={id}", bookId)
                .exchange()
                .expectBody().jsonPath("$.content.length()").isEqualTo(3));
    }

    @Test
    void listsStockLevelsAsPages() {
        BookId bookId = createBook("9780441172719");
        restock(bookId, 3).expectStatus().isOk();

        client.get().uri("/api/inventory?size=100")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[?(@.bookId == %d)].quantity".formatted(bookId.value())).isEqualTo(List.of(3))
                .jsonPath("$.page.size").isEqualTo(100);
    }

    @Test
    void returns409WhenWithdrawingMoreThanInStock() {
        BookId bookId = createBook("9780765326355");
        restock(bookId, 1).expectStatus().isOk();

        withdraw(bookId, 2).expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void returns404WhenRestockingBookNotInCatalog() {
        restock(new BookId(999_999), 1).expectStatus().isNotFound();
    }

    @Test
    void returns400ForNonPositiveQuantity() {
        BookId bookId = createBook("9780312853235");

        restock(bookId, 0).expectStatus().isBadRequest();
        withdraw(bookId, -1).expectStatus().isBadRequest();
    }

    @Test
    void refusesToDeleteBookInStock() {
        BookId bookId = createBook("9780441478125");
        restock(bookId, 2).expectStatus().isOk();

        client.delete().uri("/api/books/{id}", bookId).exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        client.get().uri("/api/books/{id}", bookId).exchange().expectStatus().isOk();
    }

    @Test
    void deletesSoldOutBookAndItsStockRecord() {
        BookId bookId = createBook("9780345391803");
        restock(bookId, 2).expectStatus().isOk();
        withdraw(bookId, 2).expectStatus().isOk();

        client.delete().uri("/api/books/{id}", bookId).exchange().expectStatus().isNoContent();

        await().untilAsserted(() -> client.get().uri("/api/inventory/{id}", bookId)
                .exchange()
                .expectStatus().isNotFound());
    }

    @Test
    void deletesBookThatWasNeverStocked() {
        BookId bookId = createBook("9780593099322");

        client.delete().uri("/api/books/{id}", bookId).exchange().expectStatus().isNoContent();
    }

    private AuthorId createAuthor() {
        return client.post().uri("/api/authors")
                .body(new AuthorRequest("Author", Set.of()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthorResponse.class)
                .returnResult().getResponseBody().id();
    }

    private BookId createBook(String isbn) {
        return client.post().uri("/api/books")
                .body(new BookRequest("Title " + isbn, List.of(createAuthor()), Set.of(), isbn, 2000, null))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookResponse.class)
                .returnResult().getResponseBody().id();
    }

    private RestTestClient.ResponseSpec restock(BookId bookId, int quantity) {
        return client.post().uri("/api/inventory/{id}/restock", bookId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new StockRequest(quantity))
                .exchange();
    }

    private RestTestClient.ResponseSpec withdraw(BookId bookId, int quantity) {
        return client.post().uri("/api/inventory/{id}/withdraw", bookId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new StockRequest(quantity))
                .exchange();
    }
}
