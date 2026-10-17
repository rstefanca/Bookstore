package cz.codingmonkey.bookstore.security;

import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tells OpenAPI clients (and Swagger UI's Authorize button) that requests sign in with HTTP Basic. Which roles each
 * endpoint needs is in {@link SecurityConfiguration}; browsing the catalog works without signing in.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiSecurity {

    static final String HTTP_BASIC = "basicAuth";

    @Bean
    OpenApiCustomizer httpBasicSignIn() {
        return api -> api
                .schemaRequirement(HTTP_BASIC, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic"))
                .addSecurityItem(new SecurityRequirement().addList(HTTP_BASIC));
    }
}
