package cz.codingmonkey.bookstore.security;

import java.security.SecureRandom;
import java.util.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Creates the first admin account when the application starts with no accounts, so someone can sign in and create
 * the others. Its password comes from {@code bookstore.security.initial-admin.password}; without one, a random
 * password is generated and logged once.
 */
@Component
class InitialAdminAccount implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(InitialAdminAccount.class);

    private final AccountService accounts;
    private final AccountProperties.InitialAdmin initialAdmin;

    InitialAdminAccount(AccountService accounts, AccountProperties properties) {
        this.accounts = accounts;
        this.initialAdmin = properties.initialAdmin();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!accounts.noAccountsExist()) {
            return;
        }
        boolean generated = initialAdmin.password() == null || initialAdmin.password().isBlank();
        String password = generated ? randomPassword() : initialAdmin.password();
        try {
            accounts.create(initialAdmin.username(), initialAdmin.firstName(), initialAdmin.lastName(), password,
                    Role.ADMIN);
        } catch (DuplicateUsernameException | DataIntegrityViolationException e) {
            return; // another instance created it at the same time
        }
        if (generated) {
            log.warn("No accounts existed, so created admin account \"{}\" with generated password: {}  "
                    + "Change it with PUT /api/accounts/me/password.", initialAdmin.username(), password);
        }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); // 24 characters
    }
}
