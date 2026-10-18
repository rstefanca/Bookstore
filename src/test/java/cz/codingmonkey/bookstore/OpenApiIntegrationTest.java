package cz.codingmonkey.bookstore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The OpenAPI spec describes the REST API as clients see it: typed ids as numbers, pages as {@code page} and
 * {@code size}, the real success statuses and problem details for errors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OpenApiIntegrationTest {

    @LocalServerPort
    int port;

    RestTestClient anonymous;

    @BeforeEach
    void setUp() {
        anonymous = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void describesTheRestApiOnly() {
        spec()
                .jsonPath("$.info.title").isEqualTo("Bookstore API")
                .jsonPath("$.components.securitySchemes.basicAuth.scheme").isEqualTo("basic")
                .jsonPath("$.paths['/api/books']").exists()
                .jsonPath("$.paths['/mcp']").doesNotExist()
                .jsonPath("$.paths['/actuator/health']").doesNotExist();
    }

    @Test
    void typedIdsAndCurrenciesAreScalars() {
        spec()
                .jsonPath("$.paths['/api/books/{id}'].get.parameters[0].name").isEqualTo("id")
                .jsonPath("$.paths['/api/books/{id}'].get.parameters[0].in").isEqualTo("path")
                .jsonPath("$.paths['/api/books/{id}'].get.parameters[0].schema.type").isEqualTo("integer")
                .jsonPath("$.paths['/api/books'].get.parameters[?(@.name == 'authorId')].schema.type")
                .isEqualTo("integer")
                .jsonPath("$.components.schemas.Money.properties.currency.type").isEqualTo("string");
    }

    @Test
    void listsTakePageAndSizeOnly() {
        String books = "$.paths['/api/books'].get.parameters";
        spec()
                .jsonPath(books + "[?(@.name == 'page')].schema.default").isEqualTo(0)
                .jsonPath(books + "[?(@.name == 'size')].schema.maximum").isEqualTo(100)
                .jsonPath(books + "[?(@.name == 'sort')]").isEmpty()
                .jsonPath(books + "[?(@.name == 'pageable')]").isEmpty()
                .jsonPath("$.paths['/api/audit'].get.parameters[?(@.name == 'parameters')]").isEmpty();
    }

    @Test
    void documentsSuccessStatusesAndProblemDetails() {
        spec()
                .jsonPath("$.paths['/api/books'].post.responses['201']").exists()
                .jsonPath("$.paths['/api/books/{id}'].delete.responses['204']").exists()
                .jsonPath("$.paths['/api/cart/checkout'].post.responses['202']").exists()
                .jsonPath("$.paths['/api/me/addresses'].get.responses['200']").exists()
                .jsonPath("$.paths['/api/me/addresses/shipping'].put.responses['200']").exists()
                .jsonPath("$.paths['/api/me/addresses/shipping'].delete.responses['204']").exists()
                .jsonPath("$.paths['/api/books/{id}'].get.responses.default.content['application/problem+json']"
                        + ".schema['$ref']").isEqualTo("#/components/schemas/Problem")
                .jsonPath("$.components.schemas.Problem.properties.errors.type").isEqualTo("array");
    }

    @Test
    void similarBooksAreStorefrontBooksWithASimilarity() {
        String similar = "$.paths['/api/books/{id}/similar'].get";
        spec()
                .jsonPath(similar + ".tags[0]").isEqualTo("Books")
                .jsonPath(similar + ".responses['200'].content['application/json'].schema.items['$ref']")
                .isEqualTo("#/components/schemas/SimilarBook")
                .jsonPath("$.components.schemas.SimilarBook.properties.id.type").isEqualTo("integer")
                .jsonPath("$.components.schemas.SimilarBook.properties.price").exists()
                .jsonPath("$.components.schemas.SimilarBook.properties.rating").exists()
                .jsonPath("$.components.schemas.SimilarBook.properties.similarity").exists();
    }

    @Test
    void yamlAndSwaggerUiAreOpen() {
        anonymous.get().uri("/v3/api-docs.yaml").exchange().expectStatus().isOk();
        anonymous.get().uri("/swagger-ui.html").exchange().expectStatus().is3xxRedirection();
        anonymous.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
        anonymous.get().uri("/v3/api-docs/swagger-config").exchange().expectStatus().isOk();
    }

    private RestTestClient.BodyContentSpec spec() {
        return anonymous.get().uri("/v3/api-docs").exchange().expectStatus().isOk().expectBody();
    }
}
