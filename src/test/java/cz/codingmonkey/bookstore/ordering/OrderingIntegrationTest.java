package cz.codingmonkey.bookstore.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.customer.TestAddresses;
import cz.codingmonkey.bookstore.inventory.StockReleased;
import cz.codingmonkey.bookstore.payment.PaymentRefunded;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * End to end over HTTP, all modules running: a customer fills a cart and checks out; inventory reserves stock and
 * the fake payment charges in the background; the order ends confirmed or rejected, with stock put back on failure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class OrderingIntegrationTest {

    @LocalServerPort
    int port;

    /** Signed in as the test admin (staff rights). */
    @Autowired
    RestTestClient staff;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IncompleteEventPublications incompletePublications;

    String buyer;
    RestTestClient customer;
    long author;

    @BeforeEach
    void setUp() {
        buyer = username("buyer");
        customer = signUp(buyer);
        saveAddresses(customer);
        author = id(staff.post().uri("/api/authors").contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\": \"Order Author\"}").exchange());
    }

    @Test
    void ordersAreConfirmedAndCanBeCancelled() {
        long dune = book("12.50", 5);
        long foundation = book("10.00", 3);
        setQuantity(dune, 2).expectStatus().isOk();
        setQuantity(foundation, 1).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(2)
                .jsonPath("$.total.amount").isEqualTo(35.00);

        long order = checkout();

        awaitStatus(order, "CONFIRMED");
        assertThat(stock(dune)).isEqualTo(3);
        assertThat(stock(foundation)).isEqualTo(2);
        customer.get().uri("/api/cart").exchange().expectBody().jsonPath("$.items").isEmpty();

        customer.post().uri("/api/orders/{id}/cancel", order).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("CANCELLED");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(stock(dune)).as("stock back").isEqualTo(5);
            assertThat(stock(foundation)).isEqualTo(3);
            assertThat(paymentStatus(order)).isEqualTo("REFUNDED");
        });
        customer.post().uri("/api/orders/{id}/cancel", order).exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);

        var audit = auditUntil(order, 7);
        assertThat(actions(audit).subList(0, 5)).containsExactly("ORDER_PLACED", "ORDER_STOCK_RESERVED",
                "ORDER_PAYMENT_CHARGED", "ORDER_CONFIRMED", "ORDER_CANCELLED");
        assertThat(actions(audit).subList(5, 7)) // undone in the background, in either order
                .containsExactlyInAnyOrder("ORDER_STOCK_RELEASED", "ORDER_PAYMENT_REFUNDED");
        assertThat(audit).extracting(entry -> entry.get("actor")).containsOnly(buyer);
    }

    @Test
    void redeliveredUndoResultsAreAuditedOnce() {
        setQuantity(book("9.99", 2), 1).expectStatus().isOk();
        long order = checkout();
        awaitStatus(order, "CONFIRMED");
        customer.post().uri("/api/orders/{id}/cancel", order).exchange().expectStatus().isOk();
        auditUntil(order, 7);

        // As if the app crashed after the order process handled the release and the refund, but before their
        // publications were marked completed: on restart they are delivered again.
        List<UUID> undone = await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForList("""
                SELECT id FROM event_publication
                 WHERE listener_id LIKE '%OrderProcess%' AND event_type IN (?, ?) AND status = 'COMPLETED'
                   AND (serialized_event::jsonb ->> 'reservationId' = ? OR serialized_event::jsonb ->> 'reference' = ?)
                """, UUID.class, StockReleased.class.getName(), PaymentRefunded.class.getName(),
                String.valueOf(order), String.valueOf(order)), ids -> ids.size() == 2);
        undone.forEach(id -> jdbc.update(
                "UPDATE event_publication SET status = 'FAILED', completion_date = NULL WHERE id = ?", id));
        incompletePublications.resubmitIncompletePublications(publication ->
                undone.contains(publication.getIdentifier()));

        await().atMost(Duration.ofSeconds(10)).until(() -> undone.stream().allMatch(id -> "COMPLETED".equals(
                jdbc.queryForObject("SELECT status FROM event_publication WHERE id = ?", String.class, id))));
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(actions(audit(order))).hasSize(7)
                        .containsOnlyOnce("ORDER_STOCK_RELEASED", "ORDER_PAYMENT_REFUNDED"));
    }

    @Test
    void declinedPaymentRejectsTheOrderAndPutsStockBack() {
        long expensive = book("60.00", 4);
        setQuantity(expensive, 2).expectStatus().isOk(); // 120 EUR: the fake payment declines above 100

        long order = checkout();

        awaitStatus(order, "REJECTED");
        customer.get().uri("/api/orders/{id}", order).exchange()
                .expectBody().jsonPath("$.reason").value(reason -> assertThat((String) reason)
                        .startsWith("Payment failed: Declined"));
        customer.get().uri("/api/cart").exchange()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].bookId").isEqualTo(expensive)
                .jsonPath("$.items[0].quantity").isEqualTo(2);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(stock(expensive)).isEqualTo(4));
        assertThat(paymentStatus(order)).isEqualTo("DECLINED");
        assertThat(actions(auditUntil(order, 5))).containsExactly("ORDER_PLACED", "ORDER_STOCK_RESERVED",
                "ORDER_PAYMENT_DECLINED", "ORDER_REJECTED", "ORDER_STOCK_RELEASED");
    }

    @Test
    void soldBooksCannotBeDeleted() {
        long sold = book("12.50", 1);
        setQuantity(sold, 1).expectStatus().isOk();
        long order = checkout();
        awaitStatus(order, "CONFIRMED");
        assertThat(stock(sold)).isZero();

        staff.delete().uri("/api/books/{id}", sold).exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("has orders"));

        // Once the order is cancelled and the copy put back and withdrawn, nothing holds the book any more.
        customer.post().uri("/api/orders/{id}/cancel", order).exchange().expectStatus().isOk();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(stock(sold)).isEqualTo(1));
        withdraw(sold, 1);
        staff.delete().uri("/api/books/{id}", sold).exchange().expectStatus().isNoContent();
    }

    @Test
    void rejectedOrdersDontKeepBooksFromBeingDeleted() {
        long expensive = book("60.00", 2);
        setQuantity(expensive, 2).expectStatus().isOk(); // 120 EUR: the fake payment declines above 100
        long order = checkout();
        awaitStatus(order, "REJECTED");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(stock(expensive)).isEqualTo(2));
        withdraw(expensive, 2);

        staff.delete().uri("/api/books/{id}", expensive).exchange().expectStatus().isNoContent();
    }

    @Test
    void checkoutRefusesShortStockAndKeepsTheCart() {
        long scarce = book("9.99", 1);
        long plenty = book("9.99", 10);
        setQuantity(scarce, 2).expectStatus().isOk();
        setQuantity(plenty, 1).expectStatus().isOk();

        customer.post().uri("/api/cart/checkout").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.detail").isEqualTo("Not enough stock: %s (1 left). "
                        .formatted(title(scarce)) + "Change the quantities and check out again.");

        customer.get().uri("/api/cart").exchange()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(2)
                .jsonPath("$.items[0].bookId").isEqualTo(scarce)
                .jsonPath("$.items[0].quantity").isEqualTo(2);
        customer.get().uri("/api/orders").exchange().expectBody().jsonPath("$.content").isEmpty();
        assertThat(stock(scarce)).isEqualTo(1);
        assertThat(stock(plenty)).isEqualTo(10);
    }

    @Test
    void checkoutRejectsEmptyCartsAndBooksNoLongerForSale() {
        customer.post().uri("/api/cart/checkout").exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);

        long book = book("9.99", 1);
        setQuantity(book, 1).expectStatus().isOk();
        staff.delete().uri("/api/books/{id}/price", book).exchange().expectStatus().isNoContent();

        customer.get().uri("/api/cart").exchange().expectBody().jsonPath("$.items[0].unitPrice").isEmpty();
        customer.post().uri("/api/cart/checkout").exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cartRules() {
        long book = book("9.99", 1);
        long unpriced = id(staff.post().uri("/api/books").contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\": \"Unpriced\", \"authorIds\": [%d], \"isbn\": \"%s\"}".formatted(author, isbn()))
                .exchange());

        setQuantity(unpriced, 1).expectStatus().isEqualTo(HttpStatus.CONFLICT);
        setQuantity(book, 21).expectStatus().isBadRequest();
        setQuantity(book, 3).expectStatus().isOk();
        setQuantity(book, 1).expectStatus().isOk().expectBody().jsonPath("$.items[0].quantity").isEqualTo(1);
        customer.delete().uri("/api/cart/items/{id}", book).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.items").isEmpty();
        setQuantity(book, 2).expectStatus().isOk();
        customer.delete().uri("/api/cart").exchange().expectStatus().isNoContent();
        customer.get().uri("/api/cart").exchange().expectBody().jsonPath("$.items").isEmpty();
    }

    @Test
    void ordersArePrivateAndStaffSeeThemAll() {
        long book = book("9.99", 5);
        setQuantity(book, 1).expectStatus().isOk();
        long order = checkout();

        signUp(username("other")).get().uri("/api/orders/{id}", order).exchange().expectStatus().isNotFound();
        customer.get().uri("/api/orders").exchange()
                .expectBody().jsonPath("$.content[0].id").isEqualTo(order);
        staff.get().uri("/api/orders/all?size=100").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.content[?(@.id == %d)].customer".formatted(order)).exists();

        customer.get().uri("/api/orders/all").exchange().expectStatus().isForbidden();
        staff.get().uri("/api/cart").exchange().expectStatus().isForbidden();
        RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/cart").exchange().expectStatus().isUnauthorized();
    }

    private long checkout() {
        var created = customer.post().uri("/api/cart/checkout").exchange()
                .expectStatus().isAccepted()
                .expectHeader().exists("Location")
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(created.get("status")).isEqualTo("PENDING");
        return ((Number) created.get("id")).longValue();
    }

    private void awaitStatus(long order, String status) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> customer.get().uri("/api/orders/{id}", order)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo(status));
    }

    private RestTestClient.ResponseSpec setQuantity(long book, int quantity) {
        return customer.put().uri("/api/cart/items/{id}", book).contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": %d}".formatted(quantity)).exchange();
    }

    /** A book with a price and stock, created by staff. */
    private long book(String price, int stock) {
        String isbn = isbn();
        long id = id(staff.post().uri("/api/books").contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\": \"Book %s\", \"authorIds\": [%d], \"isbn\": \"%s\"}".formatted(isbn, author, isbn))
                .exchange());
        staff.put().uri("/api/books/{id}/price", id).contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\": %s}".formatted(price)).exchange().expectStatus().isOk();
        staff.post().uri("/api/inventory/{id}/restock", id).contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": %d}".formatted(stock)).exchange().expectStatus().isOk();
        return id;
    }

    private void withdraw(long book, int copies) {
        staff.post().uri("/api/inventory/{id}/withdraw", book).contentType(MediaType.APPLICATION_JSON)
                .body("{\"quantity\": %d}".formatted(copies)).exchange().expectStatus().isOk();
    }

    private String title(long book) {
        return jdbc.queryForObject("SELECT title FROM book WHERE id = ?", String.class, book);
    }

    private int stock(long book) {
        return jdbc.queryForObject("SELECT quantity FROM inventory_item WHERE book_id = ?", Integer.class, book);
    }

    private String paymentStatus(long order) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE reference = ?", String.class, order);
    }

    /** The order's audit entries, oldest first, once there are at least {@code count} of them. */
    private List<Map<String, Object>> auditUntil(long order, int count) {
        return await().atMost(Duration.ofSeconds(10)).until(() -> audit(order), entries -> entries.size() >= count);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> audit(long order) {
        Map<String, Object> page = staff.get()
                .uri("/api/audit?subjectType=ORDER&subjectId={id}&size=50", order)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        var entries = new ArrayList<>((List<Map<String, Object>>) page.get("content"));
        Collections.reverse(entries); // the API lists newest first
        return entries;
    }

    private static List<Object> actions(List<Map<String, Object>> entries) {
        return entries.stream().map(entry -> entry.get("action")).toList();
    }

    private static String username(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private RestTestClient signUp(String username) {
        RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/accounts/register").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username": "%s", "firstName": "Bea", "lastName": "Buyer", "password": "buyer-password-1"}
                        """.formatted(username))
                .exchange().expectStatus().isCreated();
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(username, "buyer-password-1"))
                .build();
    }

    private static void saveAddresses(RestTestClient client) {
        client.put().uri("/api/me/addresses/shipping").contentType(MediaType.APPLICATION_JSON)
                .body(TestAddresses.json(TestAddresses.SHIPPING)).exchange().expectStatus().isOk();
        client.put().uri("/api/me/addresses/invoice").contentType(MediaType.APPLICATION_JSON)
                .body(TestAddresses.json(TestAddresses.INVOICE)).exchange().expectStatus().isOk();
    }

    private static String isbn() {
        return "ord-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private static long id(RestTestClient.ResponseSpec created) {
        return ((Number) created.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody()
                .get("id")).longValue();
    }
}
