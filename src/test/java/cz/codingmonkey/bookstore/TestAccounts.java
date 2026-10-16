package cz.codingmonkey.bookstore;

import org.springframework.boot.resttestclient.autoconfigure.RestTestClientBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The admin account every test context starts with (see {@code src/test/resources/application.properties}), and
 * default HTTP Basic credentials for the auto-configured {@code RestTestClient}, so tests about other features can
 * call any endpoint. Security tests build their own clients without these defaults.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestAccounts {

    public static final String ADMIN_USERNAME = "test-admin";
    public static final String ADMIN_PASSWORD = "test-admin-password";

    @Bean
    RestTestClientBuilderCustomizer signInAsAdmin() {
        return builder -> builder.defaultHeaders(headers -> headers.setBasicAuth(ADMIN_USERNAME, ADMIN_PASSWORD));
    }
}
