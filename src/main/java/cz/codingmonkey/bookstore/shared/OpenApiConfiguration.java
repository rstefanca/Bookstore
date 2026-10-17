package cz.codingmonkey.bookstore.shared;

import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * The OpenAPI description of the REST API ({@code /v3/api-docs}, Swagger UI at {@code /swagger-ui.html}), generated
 * by springdoc from the controllers. Descriptions come from the Javadoc of controller methods and of request and
 * response records (kept at runtime by therapi-runtime-javadoc), so write those for API clients; annotations add
 * what Javadoc can't say (tags, error statuses, examples). Modules add what only they know through their own
 * {@link OpenApiCustomizer}s (e.g. security's sign-in scheme). The overview is {@code openapi/overview.md}.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    private static final String PAGEABLE_SCHEMA = "#/components/schemas/Pageable";
    private static final String PROBLEM_SCHEMA = "#/components/schemas/Problem";
    private static final Pattern CODE = Pattern.compile("<code>(.*?)</code>");

    @Bean
    OpenAPI bookstoreApi(ObjectProvider<BuildProperties> build,
            @Value("classpath:openapi/overview.md") Resource overview) throws IOException {
        BuildProperties properties = build.getIfAvailable();
        return new OpenAPI().info(new Info()
                .title("Bookstore API")
                .version(properties != null ? properties.getVersion() : "dev")
                .description(overview.getContentAsString(StandardCharsets.UTF_8)));
    }

    /**
     * Documents the types Jackson writes as a single JSON value the way they're written: typed ids as numbers
     * ({@link LongId}, also when bound from a path or query parameter; example 1, as 0 isn't a valid id) and
     * currencies as their ISO 4217 code.
     */
    @Bean
    ModelConverter jsonScalars() {
        return (type, context, chain) -> {
            Class<?> scalar = scalarFor(TypeFactory.defaultInstance().constructType(type.getType()).getRawClass());
            AnnotatedType resolved = scalar == null ? type : new AnnotatedType(scalar)
                    .ctxAnnotations(type.getCtxAnnotations())
                    .parent(type.getParent())
                    .propertyName(type.getPropertyName())
                    .schemaProperty(type.isSchemaProperty());
            Schema<?> schema = chain.hasNext() ? chain.next().resolve(resolved, context, chain) : null;
            if (schema != null && Long.class.equals(scalar) && schema.getExample() == null) {
                schema.setExample(1);
            }
            return schema;
        };
    }

    /**
     * Lists take a Spring Data {@code Pageable}, which springdoc offers as one object parameter; clients send
     * {@code page} and {@code size} instead. There's no {@code sort}: every list has a fixed order and ignores it.
     */
    @Bean
    OpenApiCustomizer pageParameters(@Value("${spring.data.web.pageable.default-page-size:20}") int defaultSize,
            @Value("${spring.data.web.pageable.max-page-size:2000}") int maxSize) {
        return api -> {
            api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                List<Parameter> parameters = operation.getParameters();
                if (parameters != null && parameters.removeIf(parameter -> parameter.getSchema() != null
                        && PAGEABLE_SCHEMA.equals(parameter.getSchema().get$ref()))) {
                    parameters.add(new QueryParameter().name("page").description("Page number, 0-based")
                            .schema(new IntegerSchema().minimum(BigDecimal.ZERO)._default(0)));
                    parameters.add(new QueryParameter().name("size").description("Page size")
                            .schema(new IntegerSchema().minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(maxSize))
                                    ._default(defaultSize)));
                }
            }));
            api.getComponents().getSchemas().remove("Pageable");
        };
    }

    /**
     * Error responses, so operations only declare the statuses specific to them (e.g.
     * {@code @ApiResponse(responseCode = "404", description = "No book with this id")}): every 4xx and 5xx response
     * is a problem detail, operations with a request body can answer 400, and those that need a signed-in account
     * (no {@code @SecurityRequirements} override) 401 and 403. Responses are listed by status, {@code default} last.
     */
    @Bean
    OpenApiCustomizer errorResponses() {
        return api -> api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
            ApiResponses responses = operation.getResponses();
            if (operation.getRequestBody() != null) {
                responses.putIfAbsent("400", new ApiResponse()
                        .description("Malformed JSON or invalid fields; `errors` lists each invalid field"));
            }
            if (operation.getSecurity() == null) {
                responses.putIfAbsent("401", new ApiResponse().description("Not signed in, or wrong credentials"));
                responses.putIfAbsent("403", new ApiResponse().description("The account's role may not do this"));
            }
            responses.forEach((status, response) -> {
                if (status.startsWith("4") || status.startsWith("5")) {
                    response.setContent(problemContent());
                }
            });
            ApiResponses sorted = new ApiResponses();
            responses.entrySet().stream()
                    .sorted(Comparator.comparing(response -> "default".equals(response.getKey()) ? "999"
                            : response.getKey()))
                    .forEach(response -> sorted.addApiResponse(response.getKey(), response.getValue()));
            operation.setResponses(sorted);
        }));
    }

    /**
     * Tidies texts taken from Javadoc: the summary is the first sentence (plain text, without the period), so the
     * description leaves it out; {@code <p>} starts a Markdown paragraph, continuation lines are joined and HTML
     * escapes undone.
     */
    @Bean
    OpenApiCustomizer javadocTexts() {
        return api -> {
            api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                String summary = operation.getSummary();
                String description = operation.getDescription();
                if (summary != null && description != null && description.startsWith(summary)) {
                    description = description.substring(summary.length()).replaceFirst("^\\.", "");
                }
                String tidySummary = markdown(summary);
                // Swagger UI shows summaries as plain text, so no Markdown there.
                operation.setSummary(tidySummary == null ? null
                        : tidySummary.replace("`", "").replaceFirst("\\.$", ""));
                operation.setDescription(markdown(description));
                if (operation.getParameters() != null) {
                    operation.getParameters().forEach(parameter -> parameter.setDescription(
                            markdown(parameter.getDescription())));
                }
            }));
            if (api.getTags() != null) {
                api.getTags().forEach(tag -> tag.setDescription(markdown(tag.getDescription())));
            }
            api.getComponents().getSchemas().values().forEach(OpenApiConfiguration::tidyDescriptions);
        };
    }

    private static void tidyDescriptions(Schema<?> schema) {
        schema.setDescription(markdown(schema.getDescription()));
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(property -> property.setDescription(
                    markdown(property.getDescription())));
        }
    }

    private static @Nullable String markdown(@Nullable String javadoc) {
        if (javadoc == null) {
            return null;
        }
        String text = CODE.matcher(javadoc).replaceAll("`$1`")
                .replaceAll("\\s*<p>\\s*", "\n\n")
                .replaceAll("(?<!\n)\n[ \t]+(?=\\S)", " ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
                .strip();
        return text.isEmpty() ? null : text;
    }

    private static Content problemContent() {
        return new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                new MediaType().schema(new Schema<>().$ref(PROBLEM_SCHEMA)));
    }

    private static @Nullable Class<?> scalarFor(Class<?> type) {
        if (LongId.class.isAssignableFrom(type)) {
            return Long.class;
        }
        return Currency.class.equals(type) ? String.class : null;
    }
}
