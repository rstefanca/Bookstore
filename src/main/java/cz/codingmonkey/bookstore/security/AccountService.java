package cz.codingmonkey.bookstore.security;

import cz.codingmonkey.bookstore.shared.AccountId;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class AccountService implements Accounts {

    static final int MIN_PASSWORD_LENGTH = 12;

    /** BCrypt only uses the first 72 bytes; longer passwords would be silently truncated. */
    static final int MAX_PASSWORD_BYTES = 72;

    private static final String TOMBSTONE_PREFIX = "~deleted-";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,100}");
    private static final ChangeLog changes = ChangeLog.of(AccountService.class);

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final ObjectProvider<AccountDeletionPolicy> deletionPolicies;

    AccountService(AccountRepository accounts, PasswordEncoder passwordEncoder,
            ObjectProvider<AccountDeletionPolicy> deletionPolicies) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.deletionPolicies = deletionPolicies;
    }

    @Override
    public Map<AccountId, String> usernamesOf(Collection<AccountId> ids) {
        Map<AccountId, String> names = new HashMap<>();
        ids.forEach(id -> names.put(id, DELETED_USER));
        accounts.findAllByIdInAndDeletedAtIsNull(ids).forEach(account -> names.put(account.getId(), account.getUsername()));
        return names;
    }

    Page<AccountResponse> findAll(Pageable page) {
        return accounts.findAllByDeletedAtIsNull(page).map(AccountResponse::from);
    }

    AccountResponse findByUsername(String username) {
        return accounts.findByUsernameIgnoreCaseAndDeletedAtIsNull(username).map(AccountResponse::from)
                .orElseThrow(() -> new InvalidAccountException("No account " + username));
    }

    boolean noAccountsExist() {
        return accounts.count() == 0;
    }

    /**
     * @param firstName required for accounts created through the API; may be {@code null} only for the initial
     *                  admin when no name is configured
     */
    @Transactional
    AccountResponse create(String username, @Nullable String firstName, @Nullable String lastName, String password,
            Role role) {
        if (!USERNAME.matcher(username).matches()) {
            throw new InvalidAccountException(
                    "Usernames have 3-100 characters: letters, digits, '.', '_' or '-'");
        }
        checkPassword(password);
        if (accounts.existsByUsernameIgnoreCaseAndDeletedAtIsNull(username)) {
            throw new DuplicateUsernameException(username);
        }
        Account account = accounts.save(new Account(username, strip(firstName), strip(lastName),
                passwordEncoder.encode(password), role));
        changes.info("Created account {} \"{}\" with role {}", account.getId(), username, role);
        return AccountResponse.from(account);
    }

    /** Anyone signs up as a customer; same rules as accounts created by admins. */
    @Transactional
    AccountResponse register(String username, String firstName, String lastName, String password) {
        return create(username, firstName, lastName, password, Role.CUSTOMER);
    }

    /** An admin corrects someone's name. */
    @Transactional
    AccountResponse rename(AccountId id, String firstName, String lastName) {
        return rename(get(id), firstName, lastName);
    }

    /** Anyone changes their own name. */
    @Transactional
    AccountResponse renameOwn(String username, String firstName, String lastName) {
        return rename(accounts.findByUsernameIgnoreCaseAndDeletedAtIsNull(username)
                .orElseThrow(() -> new InvalidAccountException("No account " + username)), firstName, lastName);
    }

    private AccountResponse rename(Account account, String firstName, String lastName) {
        account.rename(strip(firstName), strip(lastName));
        changes.info("Renamed account {} \"{}\"", account.getId(), account.getUsername()); // no names: personal data
        return AccountResponse.from(account);
    }

    /** An admin sets a new password, e.g. when someone forgot theirs. */
    @Transactional
    void resetPassword(AccountId id, String password) {
        checkPassword(password);
        Account account = get(id);
        account.changePasswordHash(passwordEncoder.encode(password));
        changes.info("Reset password of account {} \"{}\"", id, account.getUsername());
    }

    /** Anyone changes their own password, confirming the current one. */
    @Transactional
    void changeOwnPassword(String username, String currentPassword, String newPassword) {
        Account account = accounts.findByUsernameIgnoreCaseAndDeletedAtIsNull(username)
                .orElseThrow(() -> new InvalidAccountException("No account " + username));
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new InvalidAccountException("The current password is incorrect");
        }
        checkPassword(newPassword);
        account.changePasswordHash(passwordEncoder.encode(newPassword));
        changes.info("Account {} \"{}\" changed its password", account.getId(), account.getUsername());
    }

    /**
     * Deletes an account. You can't delete your own account, and there is always at least one admin left: admin
     * rows are locked while checking, so two admins can't delete each other at the same time.
     * <p>
     * If any {@link AccountDeletionPolicy} holds records of the account, it is soft-deleted (anonymized) instead
     * of removed.
     */
    @Transactional
    void delete(AccountId id, String currentUsername) {
        Account account = get(id);
        if (account.getUsername().equalsIgnoreCase(currentUsername)) {
            throw new LastAdminException("You can't delete your own account");
        }
        if (account.getRole() == Role.ADMIN
                && accounts.findAndLockByRoleAndDeletedAtIsNull(Role.ADMIN).size() <= 1) {
            throw new LastAdminException("Can't delete the last admin account");
        }
        // Lock first so no other module can change what the policies are about to check.
        accounts.findAndLockById(id).filter(locked -> locked.getDeletedAt() == null)
                .orElseThrow(() -> new AccountNotFoundException(id));
        List<AccountDeletionPolicy> policies = deletionPolicies.orderedStream().toList();
        boolean holdsRecords = policies.stream().anyMatch(policy -> policy.holdsRecords(id));
        policies.forEach(policy -> policy.removePersonalData(id));
        if (holdsRecords) {
            policies.forEach(policy -> policy.anonymize(id));
            account.anonymize(TOMBSTONE_PREFIX + id.value(), passwordEncoder.encode(UUID.randomUUID().toString()),
                    Instant.now());
            changes.info("Anonymized account {}", id);
        } else {
            accounts.delete(account);
            changes.info("Deleted account {}", id);
        }
    }

    private void checkPassword(String password) {
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidAccountException(
                    "Passwords need at least %d characters".formatted(MIN_PASSWORD_LENGTH));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidAccountException(
                    "Passwords can be at most %d bytes long".formatted(MAX_PASSWORD_BYTES));
        }
    }

    private static @Nullable String strip(@Nullable String name) {
        return name == null ? null : name.strip();
    }

    private Account get(AccountId id) {
        return accounts.findById(id).filter(account -> account.getDeletedAt() == null).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
