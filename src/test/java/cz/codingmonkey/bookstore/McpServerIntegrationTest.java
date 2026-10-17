package cz.codingmonkey.bookstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.AuthorResponse;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookRequest;
import cz.codingmonkey.bookstore.bookcatalog.internal.BookResponse;
import cz.codingmonkey.bookstore.bookcatalog.internal.GenreRepository;
import cz.codingmonkey.bookstore.pricing.internal.PriceRequest;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Talks to the MCP endpoint the way an AI client would, using the MCP Java SDK over Streamable HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class McpServerIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    RestTestClient rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    GenreRepository genres;

    McpSyncClient mcp;

    AuthorId herbert;
    GenreId scienceFiction;
    BookId dune;
    BookId childrenOfDune;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM book");
        jdbc.update("DELETE FROM author");
        jdbc.update("DELETE FROM book_embedding");
        scienceFiction = genres.findAll().stream()
                .filter(genre -> genre.getName().equals("Science Fiction")).findFirst().orElseThrow().getId();

        herbert = createAuthor("Frank Herbert");
        dune = createBook("Dune", herbert, Set.of(scienceFiction), "9780441013593",
                "On the desert planet Arrakis, Paul Atreides fights for control of the spice melange.");
        childrenOfDune = createBook("Children of Dune", herbert, Set.of(scienceFiction), "9780441104024",
                "Paul's twin children inherit the desert world of Arrakis and its spice-fuelled empire.");
        createBook("The Joy of Cooking", createAuthor("Irma S. Rombauer"), Set.of(), "9780743246262",
                "Classic American recipes for soups, breads, roasts and desserts.");

        mcp = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).build()).build();
        mcp.initialize();
    }

    @AfterEach
    void closeClient() {
        mcp.closeGracefully();
    }

    @Test
    void exposesOnlyReadOnlyTools() {
        List<Tool> tools = mcp.listTools().tools();

        assertThat(tools).extracting(Tool::name).containsExactlyInAnyOrder(
                "search_books", "get_book", "list_authors", "get_author", "list_genres", "find_similar_books",
                "get_book_prices");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.annotations().readOnlyHint()).as(tool.name()).isTrue();
            assertThat(tool.annotations().destructiveHint()).as(tool.name()).isFalse();
        });
    }

    @Test
    void searchesBooksByTitle() {
        String json = call("search_books", Map.of("title", "dune"));

        assertThat(JsonPath.<List<String>>read(json, "$[*].title")).containsExactly("Children of Dune", "Dune");
        assertThat(JsonPath.<Integer>read(json, "$[1].id")).as("ids are plain numbers").isEqualTo((int) dune.value());
        assertThat(JsonPath.<String>read(json, "$[1].authors[0].name")).isEqualTo("Frank Herbert");
        assertThat(JsonPath.<String>read(json, "$[1].genres[0].name")).isEqualTo("Science Fiction");
        assertThat(JsonPath.<Integer>read(json, "$[1].rating.count")).as("unrated book").isZero();
        assertThat(JsonPath.<Object>read(json, "$[1].rating.average")).isNull();
    }

    @Test
    void combinesFiltersAndLimits() {
        String byGenre = call("search_books", Map.of("genreId", scienceFiction.value(), "author", "herbert"));
        assertThat(JsonPath.<List<String>>read(byGenre, "$[*].title")).containsExactly("Children of Dune", "Dune");

        String limited = call("search_books", Map.of("limit", 1));
        assertThat(JsonPath.<List<String>>read(limited, "$[*].title")).containsExactly("Children of Dune");
    }

    @Test
    void searchesBooksByPrice() {
        setPrice(dune, "14.99");
        setPrice(childrenOfDune, "12.99");

        String json = call("search_books", Map.of("title", "dune", "maxPrice", 12.99));

        assertThat(JsonPath.<List<String>>read(json, "$[*].title")).containsExactly("Children of Dune");
        assertThat(JsonPath.<Double>read(json, "$[0].price.amount")).isEqualTo(12.99);
    }

    @Test
    void getsBookAuthorAndGenres() {
        assertThat(JsonPath.<String>read(call("get_book", Map.of("bookId", dune.value())), "$.isbn"))
                .isEqualTo("9780441013593");
        assertThat(JsonPath.<String>read(call("get_author", Map.of("authorId", herbert.value())), "$.name"))
                .isEqualTo("Frank Herbert");
        assertThat(JsonPath.<List<String>>read(call("list_authors", Map.of("name", "herb")), "$[*].name"))
                .containsExactly("Frank Herbert");
        assertThat(JsonPath.<List<String>>read(call("list_genres", Map.of("name", "fiction")), "$[*].name"))
                .contains("Science Fiction", "Historical Fiction");
    }

    @Test
    void findsSimilarBooksForSale() {
        setPrice(childrenOfDune, "12.99");
        // Embeddings are computed asynchronously after the books are created.
        await().until(() -> jdbc.queryForObject("SELECT count(*) FROM book_embedding WHERE NOT deleted",
                Integer.class) == 3);

        String json = call("find_similar_books", Map.of("bookId", dune.value()));
        assertThat(JsonPath.<List<Integer>>read(json, "$[*].id")).as("the cookbook isn't for sale")
                .containsExactly((int) childrenOfDune.value());
        assertThat(JsonPath.<String>read(json, "$[0].authors[0].name")).isEqualTo("Frank Herbert");
        assertThat(JsonPath.<Double>read(json, "$[0].price.amount")).isEqualTo(12.99);
        assertThat(JsonPath.<Integer>read(json, "$[0].rating.count")).isZero();
        assertThat(JsonPath.<Double>read(json, "$[0].similarity")).isGreaterThan(0.6);

        CallToolResult unknownBook = callRaw("find_similar_books", Map.of("bookId", 999_999));
        assertThat(unknownBook.isError()).isTrue();
        assertThat(text(unknownBook)).contains("No recommendations for book 999999 yet");
    }

    @Test
    void getsBookPrices() {
        rest.put().uri("/api/books/{id}/price", dune)
                .body(new PriceRequest(new BigDecimal("12.90"), null))
                .exchange()
                .expectStatus().isOk();

        String json = call("get_book_prices", Map.of("bookIds", List.of(dune.value(), childrenOfDune.value())));

        assertThat(JsonPath.<List<Integer>>read(json, "$[*].bookId"))
                .containsExactly((int) dune.value(), (int) childrenOfDune.value());
        assertThat(JsonPath.<Double>read(json, "$[0].price.amount")).isEqualTo(12.90);
        assertThat(JsonPath.<String>read(json, "$[0].price.currency")).isEqualTo("EUR");
        assertThat(JsonPath.<Object>read(json, "$[1].price")).as("not for sale").isNull();
    }

    @Test
    void reportsErrorsAsToolErrors() {
        CallToolResult unknownBook = callRaw("get_book", Map.of("bookId", 999_999));
        assertThat(unknownBook.isError()).isTrue();
        assertThat(text(unknownBook)).contains("Book 999999 not found");

        CallToolResult badLimit = callRaw("search_books", Map.of("limit", 500));
        assertThat(badLimit.isError()).isTrue();
        assertThat(text(badLimit)).contains("limit must be between 1 and 50");

        CallToolResult badRange = callRaw("search_books", Map.of("minPrice", 20, "maxPrice", 10));
        assertThat(badRange.isError()).isTrue();
        assertThat(text(badRange)).contains("minPrice (20) can't be above maxPrice (10)");
    }

    private void setPrice(BookId book, String amount) {
        rest.put().uri("/api/books/{id}/price", book)
                .body(new PriceRequest(new BigDecimal(amount), null))
                .exchange()
                .expectStatus().isOk();
    }

    /** Calls a tool that is expected to succeed and returns its JSON result. */
    private String call(String tool, Map<String, Object> arguments) {
        CallToolResult result = callRaw(tool, arguments);
        assertThat(result.isError()).as("%s failed: %s", tool, text(result)).isNotEqualTo(Boolean.TRUE);
        return text(result);
    }

    private CallToolResult callRaw(String tool, Map<String, Object> arguments) {
        return mcp.callTool(new CallToolRequest(tool, arguments));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private AuthorId createAuthor(String name) {
        return rest.post().uri("/api/authors")
                .body(new AuthorRequest(name, Set.of()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthorResponse.class).returnResult().getResponseBody().id();
    }

    private BookId createBook(String title, AuthorId author, Set<GenreId> genreIds, String isbn, String description) {
        return rest.post().uri("/api/books")
                .body(new BookRequest(title, List.of(author), genreIds, isbn, 2000, description))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookResponse.class).returnResult().getResponseBody().id();
    }
}
