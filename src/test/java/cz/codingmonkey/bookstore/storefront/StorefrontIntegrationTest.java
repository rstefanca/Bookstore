package cz.codingmonkey.bookstore.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorResponse;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * End to end: GET /api/books and /api/books/{id} combine catalog data with prices.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class StorefrontIntegrationTest {

    private static final String PASSWORD = "buyer-password-1";

    @Autowired
    RestTestClient client;

    @LocalServerPort
    int port;

    String titlePrefix;
    AuthorId author;

    @BeforeEach
    void setUp() {
        titlePrefix = "Store " + UUID.randomUUID().toString().substring(0, 8) + " ";
        author = client.post().uri("/api/authors")
                .body(new AuthorRequest("Store Author", Set.of()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthorResponse.class).returnResult().getResponseBody().id();
    }

    @Test
    void showsTheBookWithItsPrice() {
        BookId book = createBook("A");
        setPrice(book, "12.90");

        client.get().uri("/api/books/{id}", book)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(book.value())
                .jsonPath("$.title").isEqualTo(titlePrefix + "A")
                .jsonPath("$.authors[0].name").isEqualTo("Store Author")
                .jsonPath("$.price.amount").isEqualTo(12.90)
                .jsonPath("$.price.currency").isEqualTo("EUR");
    }

    @Test
    void showsBooksWithoutAPriceAsNotForSale() {
        BookId book = createBook("A");

        client.get().uri("/api/books/{id}", book)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.price").isEmpty();
    }

    @Test
    void reflectsPriceChangesImmediately() {
        BookId book = createBook("A");
        setPrice(book, "10");
        client.get().uri("/api/books/{id}", book).exchange().expectBody().jsonPath("$.price.amount").isEqualTo(10.00);

        setPrice(book, "7.50");

        client.get().uri("/api/books/{id}", book).exchange().expectBody().jsonPath("$.price.amount").isEqualTo(7.50);
    }

    @Test
    void listsBooksWithTheirPrices() {
        BookId priced = createBook("A");
        createBook("B");
        setPrice(priced, "9.99");

        client.get().uri("/api/books?title={prefix}", titlePrefix)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(2)
                .jsonPath("$.content[0].title").isEqualTo(titlePrefix + "A")
                .jsonPath("$.content[0].price.amount").isEqualTo(9.99)
                .jsonPath("$.content[1].price").isEmpty()
                .jsonPath("$.page.totalElements").isEqualTo(2);
    }

    @Test
    void showsRatings() {
        BookId rated = createBook("A");
        createBook("B");
        rate(signUp(username("bea")), rated, 4);
        rate(signUp(username("carl")), rated, 5);

        client.get().uri("/api/books/{id}", rated)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.rating.average").isEqualTo(4.5)
                .jsonPath("$.rating.count").isEqualTo(2)
                .jsonPath("$.rating.stars.4").isEqualTo(1)
                .jsonPath("$.rating.stars.5").isEqualTo(1)
                .jsonPath("$.rating.stars.1").isEqualTo(0);

        client.get().uri("/api/books?title={prefix}", titlePrefix)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[0].rating.average").isEqualTo(4.5)
                .jsonPath("$.content[0].rating.count").isEqualTo(2)
                .jsonPath("$.content[0].rating.stars.4").isEqualTo(1)
                .jsonPath("$.content[0].rating.stars.5").isEqualTo(1)
                .jsonPath("$.content[0].rating.stars.1").isEqualTo(0)
                .jsonPath("$.content[1].rating.average").isEmpty()
                .jsonPath("$.content[1].rating.count").isEqualTo(0);
    }

    @Test
    void filtersByPriceRangeWithInclusiveBounds() {
        setPrice(createBook("Cheap"), "5");
        setPrice(createBook("Middle"), "10");
        setPrice(createBook("Pricey"), "20");
        createBook("Unpriced");

        assertTitles("maxPrice=10", "Cheap", "Middle");
        assertTitles("minPrice=10", "Middle", "Pricey");
        assertTitles("minPrice=6&maxPrice=19.99", "Middle");
        assertTitles("maxPrice=4.99");
        assertTitles("", "Cheap", "Middle", "Pricey", "Unpriced");
    }

    @Test
    void rejectsInvalidPriceRanges() {
        client.get().uri("/api/books?minPrice=20&maxPrice=10").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/books?maxPrice=-1").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/books?maxPrice=cheap").exchange().expectStatus().isBadRequest();
    }

    private void assertTitles(String priceQuery, String... titles) {
        client.get().uri("/api/books?title={prefix}&" + priceQuery, titlePrefix)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.page.totalElements").isEqualTo(titles.length)
                .jsonPath("$.content[*].title").value(found -> assertThat(found).asInstanceOf(LIST)
                        .containsExactly(Arrays.stream(titles).map(t -> titlePrefix + t).toArray()));
    }

    @Test
    void returns404ForUnknownBooks() {
        client.get().uri("/api/books/{id}", 999_999).exchange().expectStatus().isNotFound();
    }

    private BookId createBook(String titleSuffix) {
        String isbn = "store-" + UUID.randomUUID().toString().substring(0, 8);
        return client.post().uri("/api/books")
                .body(new BookRequest(titlePrefix + titleSuffix, List.of(author), Set.of(), isbn, 2000, null))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookResponse.class).returnResult().getResponseBody().id();
    }

    private void setPrice(BookId book, String amount) {
        client.put().uri("/api/books/{id}/price", book)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": " + amount + "}")
                .exchange()
                .expectStatus().isOk();
    }

    private void rate(RestTestClient customer, BookId book, int stars) {
        customer.put().uri("/api/books/{id}/rating", book)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"stars\": " + stars + "}")
                .exchange()
                .expectStatus().isOk();
    }

    private RestTestClient signUp(String username) {
        RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .build()
                .post().uri("/api/accounts/register").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "Bea", "lastName": "Buyer", "password": "%s"}
                        """.formatted(username, PASSWORD))
                .exchange().expectStatus().isCreated();
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(username, PASSWORD))
                .build();
    }

    private static String username(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
