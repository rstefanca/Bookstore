package cz.codingmonkey.bookstore.security;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what. Every endpoint is listed here, and anything not listed is denied, so a new endpoint is
 * closed until it is added.
 * <p>
 * Requests authenticate with HTTP Basic on every call; there are no sessions or cookies, which is also why CSRF
 * protection (a defence for cookie-based sessions) is off.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountProperties.class)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                        // Anyone: browse the catalog, prices and recommendations, and use the read-only MCP tools.
                        .requestMatchers(GET, "/api/books", "/api/books/*", "/api/books/*/similar",
                                "/api/books/*/price", "/api/prices").permitAll()
                        .requestMatchers(GET, "/api/authors", "/api/authors/*", "/api/genres", "/api/genres/*")
                        .permitAll()
                        .requestMatchers("/mcp", "/mcp/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Anyone: health, for load balancers and orchestrators (details only for admins).
                        .requestMatchers(GET, "/actuator/health", "/actuator/health/**").permitAll()
                        // Anyone: the OpenAPI spec and Swagger UI (they describe the API, not its data).
                        .requestMatchers(GET, "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()

                        // Customers: their cart and orders. Staff (and admins) see all orders.
                        .requestMatchers(GET, "/api/orders/all").hasRole(Role.STAFF.name())
                        .requestMatchers("/api/cart", "/api/cart/**", "/api/orders", "/api/orders/**")
                        .hasRole(Role.CUSTOMER.name())
                        // Customers: personal picks, and rate books.
                        .requestMatchers(GET, "/api/recommendations").hasRole(Role.CUSTOMER.name())
                        .requestMatchers(GET, "/api/books/*/rating").hasRole(Role.CUSTOMER.name())
                        .requestMatchers(PUT, "/api/books/*/rating").hasRole(Role.CUSTOMER.name())
                        // Staff (and admins): maintain the catalog, prices and inventory.
                        .requestMatchers("/api/books/**", "/api/authors/**", "/api/genres/**", "/api/inventory/**")
                        .hasRole(Role.STAFF.name())

                        // Anyone: sign up as a customer.
                        .requestMatchers(POST, "/api/accounts/register").permitAll()
                        // Every signed-in account: its own details and password.
                        .requestMatchers("/api/accounts/me", "/api/accounts/me/**").authenticated()
                        // Admins: audit log, account management and the other operational endpoints.

                        .requestMatchers("/api/audit/**", "/api/accounts/**").hasRole(Role.ADMIN.name())
                        .requestMatchers("/actuator", "/actuator/**").hasRole(Role.ADMIN.name())

                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /** Everything a staff member may do, an admin may do too. */
    @Bean
    RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix().role(Role.ADMIN.name()).implies(Role.STAFF.name()).build();
    }

    /** BCrypt, with hashes tagged by algorithm (e.g. "{bcrypt}...") so it can be upgraded later. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
