package cz.codingmonkey.bookstore.wishlist;

import static org.assertj.core.api.Assertions.assertThat;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Customers keep a wishlist over HTTP, all modules running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class WishlistIntegrationTest {

    private static final String PASSWORD = "buyer-password-1";

    @LocalServerPort
    int port;

    /** Signed in as the test admin (staff rights). */
    @Autowired
    RestTestClient staff;

    @Autowired
    JdbcTemplate jdbc;

    String username;
    RestTestClient customer;
    long accountId;
    long book;

    @BeforeEach
    void setUp() {
        username = username("wisher");
        customer = signUp(username);
        accountId = jdbc.queryForObject("SELECT id FROM account WHERE username = ?", Long.class, username);
        book = createBook();
    }

    @Test
    void addPutsTheBookOnTheList() {
        put(customer, book).expectStatus().isNoContent();

        assertThat(count(accountId, book)).isEqualTo(1);
    }

    @Test
    void addingTwiceKeepsTheFirstAddedAt() {
        put(customer, book).expectStatus().isNoContent();
        Object first = jdbc.queryForObject(
                "SELECT added_at FROM wishlist_item WHERE account_id = ? AND book_id = ?", Object.class, accountId, book);

        put(customer, book).expectStatus().isNoContent();

        assertThat(count(accountId, book)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT added_at FROM wishlist_item WHERE account_id = ? AND book_id = ?", Object.class, accountId, book))
                .isEqualTo(first);
    }

    @Test
    void removeDeletesTheEntry() {
        put(customer, book).expectStatus().isNoContent();

        customer.delete().uri("/api/me/wishlist/{id}", book).exchange().expectStatus().isNoContent();

        assertThat(count(accountId, book)).isZero();
    }

    @Test
    void removingAMissingEntryIsStillNoContent() {
        customer.delete().uri("/api/me/wishlist/{id}", book).exchange().expectStatus().isNoContent();
    }

    @Test
    void unknownBookIs404() {
        put(customer, 987_654_321L).expectStatus().isNotFound();
    }

    @Test
    void nonNumericBookIdIs400() {
        customer.put().uri("/api/me/wishlist/abc").exchange().expectStatus().isBadRequest();
    }

    @Test
    void fullListRejectsANewBook() {
        fill(200);

        put(customer, book).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").value(detail ->
                        assertThat(detail.toString()).contains("Your wishlist is full: at most 200 books"));
        assertThat(count(accountId, book)).isZero();
    }

    @Test
    void addingAListedBookToAFullListIsNoContent() {
        fill(199);
        put(customer, book).expectStatus().isNoContent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wishlist_item WHERE account_id = ?", Integer.class,
                accountId)).isEqualTo(200);

        put(customer, book).expectStatus().isNoContent();
    }

    @Test
    void staffAndAdminAreForbidden() {
        put(staff, book).expectStatus().isForbidden();
        staff.delete().uri("/api/me/wishlist/{id}", book).exchange().expectStatus().isForbidden();
    }

    @Test
    void deletingACustomerRemovesTheirWishlist() {
        put(customer, book).expectStatus().isNoContent();

        staff.delete().uri("/api/accounts/{id}", accountId).exchange().expectStatus().isNoContent();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM wishlist_item WHERE account_id = ?", Integer.class,
                accountId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE id = ?", Integer.class, accountId))
                .isZero();
    }

    @Test
    void listsNewestAddedFirstWithPriceAndRating() {
        long first = createBook("First");
        long second = createBook("Second");
        long third = createBook("Third");
        setPrice(second, "12.50");
        rate(customer, second, 4);
        put(customer, first).expectStatus().isNoContent();
        put(customer, second).expectStatus().isNoContent();
        put(customer, third).expectStatus().isNoContent();

        customer.get().uri("/api/me/wishlist").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(3)
                .jsonPath("$.content[0].book.title").isEqualTo("Third")
                .jsonPath("$.content[1].book.title").isEqualTo("Second")
                .jsonPath("$.content[2].book.title").isEqualTo("First")
                .jsonPath("$.content[1].book.price.amount").isEqualTo(12.5)
                .jsonPath("$.content[1].book.rating.count").isEqualTo(1)
                .jsonPath("$.content[1].addedAt").isNotEmpty();
    }

    @Test
    void aBookWithoutAPriceIsListedWithNullPrice() {
        put(customer, book).expectStatus().isNoContent();

        customer.get().uri("/api/me/wishlist").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(1)
                .jsonPath("$.content[0].book.id").isEqualTo(book)
                .jsonPath("$.content[0].book.price").isEmpty();
    }

    @Test
    void anEmptyWishlistIsAnEmptyPage() {
        customer.get().uri("/api/me/wishlist").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(0)
                .jsonPath("$.page.totalElements").isEqualTo(0);
    }

    @Test
    void pagingSplitsTheList() {
        for (int i = 0; i < 3; i++) {
            put(customer, createBook()).expectStatus().isNoContent();
        }

        customer.get().uri("/api/me/wishlist?size=2&page=0").exchange().expectBody()
                .jsonPath("$.content.length()").isEqualTo(2)
                .jsonPath("$.page.totalElements").isEqualTo(3);
        customer.get().uri("/api/me/wishlist?size=2&page=1").exchange().expectBody()
                .jsonPath("$.content.length()").isEqualTo(1);
    }

    @Test
    void customersSeeOnlyTheirOwnList() {
        RestTestClient other = signUp(username("other"));
        put(customer, book).expectStatus().isNoContent();

        other.get().uri("/api/me/wishlist").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.content.length()").isEqualTo(0);
        customer.get().uri("/api/me/wishlist").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.content.length()").isEqualTo(1);
    }

    @Test
    void anEntryOfAMissingBookIsSkipped() {
        // What a deleted book leaves behind until the async cleanup has run.
        jdbc.update("INSERT INTO wishlist_item (account_id, book_id, added_at) VALUES (?, ?, now() + interval '1 minute')",
                accountId, 987_654_321L);
        put(customer, book).expectStatus().isNoContent();

        customer.get().uri("/api/me/wishlist").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(1)
                .jsonPath("$.content[0].book.id").isEqualTo(book);
    }

    @Test
    void listIsForbiddenForStaff() {
        staff.get().uri("/api/me/wishlist").exchange().expectStatus().isForbidden();
    }

    @Test
    void anonymousIs401() {
        RestTestClient anonymous = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

        put(anonymous, book).expectStatus().isUnauthorized();
    }

    private void setPrice(long bookId, String amount) {
        staff.put().uri("/api/books/{id}/price", bookId).contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": " + amount + "}").exchange().expectStatus().isOk();
    }

    private void rate(RestTestClient client, long bookId, int stars) {
        client.put().uri("/api/books/{id}/rating", bookId).contentType(MediaType.APPLICATION_JSON)
                .body("{\"stars\": " + stars + "}").exchange().expectStatus().isOk();
    }

    private RestTestClient.ResponseSpec put(RestTestClient client, long bookId) {
        return client.put().uri("/api/me/wishlist/{id}", bookId).exchange();
    }

    /** Puts {@code n} made-up books on the customer's list; there is no foreign key to books. */
    private void fill(int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update("INSERT INTO wishlist_item (account_id, book_id, added_at) VALUES (?, ?, now())",
                    accountId, -1L - i);
        }
    }

    private int count(long account, long bookId) {
        return jdbc.queryForObject("SELECT count(*) FROM wishlist_item WHERE account_id = ? AND book_id = ?",
                Integer.class, account, bookId);
    }

    private long createBook() {
        return createBook("Book " + UUID.randomUUID().toString().substring(0, 8));
    }

    private long createBook(String title) {
        String isbn = "wsh-" + UUID.randomUUID().toString().substring(0, 12);
        var author = staff.post().uri("/api/authors").contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\": \"Wished Author\"}").exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        long authorId = ((Number) author.get("id")).longValue();
        var created = staff.post().uri("/api/books").contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\": \"%s\", \"authorIds\": [%d], \"isbn\": \"%s\"}".formatted(title, authorId, isbn))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return ((Number) created.get("id")).longValue();
    }

    private RestTestClient signUp(String username) {
        RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/accounts/register").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "Wilma", "lastName": "Wisher", "password": "%s"}
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
