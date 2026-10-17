package cz.codingmonkey.bookstore.shared;

import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI description of the REST API ({@code /v3/api-docs}, Swagger UI at {@code /swagger-ui.html}), generated
 * by springdoc from the controllers. Modules add what only they know through their own {@link OpenApiCustomizer}s
 * (e.g. security's sign-in scheme).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    private static final String PAGEABLE_SCHEMA = "#/components/schemas/Pageable";

    @Bean
    OpenAPI bookstoreApi(ObjectProvider<BuildProperties> build) {
        BuildProperties properties = build.getIfAvailable();
        return new OpenAPI().info(new Info()
                .title("Bookstore API")
                .version(properties != null ? properties.getVersion() : "dev")
                .description("""
                        Catalog, prices, inventory, carts and orders. Errors are RFC 9457 problem details \
                        (application/problem+json). List endpoints return pages with a fixed order."""));
    }

    /**
     * Documents the types Jackson writes as a single JSON value the way they're written: typed ids as numbers
     * ({@link LongId}, also when bound from a path or query parameter) and currencies as their ISO 4217 code.
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
            return chain.hasNext() ? chain.next().resolve(resolved, context, chain) : null;
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

    private static @Nullable Class<?> scalarFor(Class<?> type) {
        if (LongId.class.isAssignableFrom(type)) {
            return Long.class;
        }
        return Currency.class.equals(type) ? String.class : null;
    }
}
