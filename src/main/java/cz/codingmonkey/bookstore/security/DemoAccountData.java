package cz.codingmonkey.bookstore.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * With the {@code demo} profile, creates the staff accounts in {@code demo-data/accounts.json} whose usernames don't
 * exist yet, so restarts create nothing twice. Runs after {@link InitialAdminAccount}, which only creates the admin
 * while there are no accounts at all.
 */
@Component
@Profile("demo")
@Order(Ordered.LOWEST_PRECEDENCE)
class DemoAccountData implements ApplicationRunner {

    static final String LOCATION = "demo-data/accounts.json";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(List<Entry> accounts) {
    }

    record Entry(String username, String firstName, String lastName, String password) {
    }

    private final AccountService accounts;
    private final List<Entry> entries;

    DemoAccountData(AccountService accounts, JsonMapper json) throws IOException {
        this.accounts = accounts;
        try (InputStream in = new ClassPathResource(LOCATION).getInputStream()) {
            this.entries = json.readValue(in, Data.class).accounts();
        }
    }

    @Override
    public void run(@Nullable ApplicationArguments args) {
        for (Entry entry : entries) {
            try {
                accounts.create(entry.username(), entry.firstName(), entry.lastName(), entry.password(), Role.STAFF);
            } catch (DuplicateUsernameException e) {
                // created on an earlier start
            }
        }
    }
}
