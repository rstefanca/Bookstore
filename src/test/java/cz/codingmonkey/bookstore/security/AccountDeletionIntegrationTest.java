package cz.codingmonkey.bookstore.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cz.codingmonkey.bookstore.TestAccountIds;
import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.shared.AccountId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Deleting accounts: hard delete when no module keeps records, otherwise soft delete (anonymize). A fake
 * {@link AccountDeletionPolicy} stands in for the modules that hold records.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, AccountDeletionIntegrationTest.FakePolicyConfiguration.class})
class AccountDeletionIntegrationTest {

    static final String PASSWORD = "a-long-password";

    static class FakePolicy implements AccountDeletionPolicy {
        final Set<AccountId> holding = ConcurrentHashMap.newKeySet();
        final List<AccountId> removed = new CopyOnWriteArrayList<>();
        final List<AccountId> anonymized = new CopyOnWriteArrayList<>();

        @Override
        public boolean holdsRecords(AccountId account) {
            return holding.contains(account);
        }

        @Override
        public void removePersonalData(AccountId account) {
            removed.add(account);
        }

        @Override
        public void anonymize(AccountId account) {
            anonymized.add(account);
        }

        void reset() {
            holding.clear();
            removed.clear();
            anonymized.clear();
        }
    }

    @TestConfiguration
    static class FakePolicyConfiguration {
        @Bean
        FakePolicy fakePolicy() {
            return new FakePolicy();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    RestTestClient admin;

    @Autowired
    FakePolicy policy;

    @Autowired
    AccountService service;

    @Autowired
    Accounts accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    EntityManager entityManager;

    String username;

    @BeforeEach
    void setUp() {
        policy.reset();
        username = "del-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void hardDeleteWhenNobodyHoldsRecords() {
        long id = createdId(username, "STAFF");

        admin.delete().uri("/api/accounts/{id}", id).exchange().expectStatus().isNoContent();

        assertThat(jdbc.queryForObject("select count(*) from account where id = ?", Integer.class, id)).isZero();
        assertThat(policy.removed).containsExactly(new AccountId(id));
        assertThat(policy.anonymized).isEmpty();
    }

    @Test
    void softDeleteWhenSomeoneHoldsRecords() {
        long id = createdId(username, "CUSTOMER");
        policy.holding.add(new AccountId(id));

        admin.delete().uri("/api/accounts/{id}", id).exchange().expectStatus().isNoContent();

        Map<String, Object> row = jdbc.queryForMap("select * from account where id = ?", id);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("username")).isEqualTo("~deleted-" + id);
        assertThat(row.get("first_name")).isNull();
        assertThat(row.get("last_name")).isNull();
        assertThat(policy.anonymized).containsExactly(new AccountId(id));
        assertThat(policy.removed).containsExactly(new AccountId(id));
    }

    @Test
    void softDeletedAccountCannotSignIn() {
        long id = createdId(username, "CUSTOMER");
        policy.holding.add(new AccountId(id));
        admin.delete().uri("/api/accounts/{id}", id).exchange().expectStatus().isNoContent();

        signedInAs(username, PASSWORD).get().uri("/api/accounts/me").exchange().expectStatus().isUnauthorized();
        signedInAs("~deleted-" + id, PASSWORD).get().uri("/api/accounts/me").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void usernameIsFreeAfterSoftDelete() {
        long id = createdId(username, "CUSTOMER");
        policy.holding.add(new AccountId(id));
        admin.delete().uri("/api/accounts/{id}", id).exchange().expectStatus().isNoContent();

        long again = createdId(username, "CUSTOMER");

        assertThat(again).isNotEqualTo(id);
    }

    @Test
    void softDeletedAccountIsHiddenFromTheList() {
        long id = createdId(username, "CUSTOMER");
        policy.holding.add(new AccountId(id));
        admin.delete().uri("/api/accounts/{id}", id).exchange().expectStatus().isNoContent();

        String body = admin.get().uri("/api/accounts?size=100").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).doesNotContain("~deleted-" + id).doesNotContain(username);
        admin.delete().uri("/api/accounts/{id}", id).exchange().expectStatus().isNotFound();
    }

    @Test
    void softDeletedAdminDoesNotCountAsAdmin() {
        long first = createdId(username, "ADMIN");
        long second = createdId(username + "-b", "ADMIN");
        List<Long> others = jdbc.queryForList(
                "select id from account where role = 'ADMIN' and id not in (?, ?)", Long.class, first, second);
        others.forEach(other -> jdbc.update("update account set role = 'STAFF' where id = ?", other));
        try {
            policy.holding.add(new AccountId(first));
            service.delete(new AccountId(first), TestAccountIds.next()); // two live admins: soft delete is fine

            assertThatThrownBy(() -> service.delete(new AccountId(second), TestAccountIds.next()))
                    .isInstanceOf(LastAdminException.class);
        } finally {
            others.forEach(other -> jdbc.update("update account set role = 'ADMIN' where id = ?", other));
        }
    }

    @Test
    void deletingYourselfIsStillRefused() {
        long id = createdId(username, "ADMIN");

        signedInAs(username, PASSWORD).delete().uri("/api/accounts/{id}", id).exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void usernamesOfMapsDeletedAndUnknownToDeletedUser() {
        long live = createdId(username, "CUSTOMER");
        long gone = createdId(username + "-x", "CUSTOMER");
        policy.holding.add(new AccountId(gone));
        admin.delete().uri("/api/accounts/{id}", gone).exchange().expectStatus().isNoContent();
        AccountId unknown = new AccountId(Long.MAX_VALUE);

        Map<AccountId, String> names = accounts.usernamesOf(
                List.of(new AccountId(live), new AccountId(gone), unknown));

        assertThat(names).containsEntry(new AccountId(live), username)
                .containsEntry(new AccountId(gone), Accounts.DELETED_USER)
                .containsEntry(unknown, Accounts.DELETED_USER)
                .hasSize(3);
    }

    @Test
    void concurrentDoubleDeleteSecondGets404() throws Exception {
        long id = createdId(username, "CUSTOMER");
        AccountId accountId = new AccountId(id);
        policy.holding.add(accountId);
        CompletableFuture<Void> second = new CompletableFuture<>();

        transaction.executeWithoutResult(status -> {
            lockRow(accountId);
            runAsync(second, () -> service.delete(accountId, TestAccountIds.next())); // loads the account, then waits for the lock
            sleep(500);
            service.delete(accountId, TestAccountIds.next());
        });

        assertThatThrownBy(() -> second.get(10, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(AccountNotFoundException.class);
        assertThat(policy.anonymized).containsExactly(accountId);
    }

    @Test
    void renameRacingASoftDeleteDoesNotResurrectTheAccount() throws Exception {
        long id = createdId(username, "CUSTOMER");
        AccountId accountId = new AccountId(id);
        policy.holding.add(accountId);
        CompletableFuture<Void> rename = new CompletableFuture<>();

        transaction.executeWithoutResult(status -> {
            lockRow(accountId);
            runAsync(rename, () -> service.rename(accountId, "New", "Name"));
            sleep(500);
            service.delete(accountId, TestAccountIds.next());
        });

        assertThatThrownBy(() -> rename.get(10, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(AccountNotFoundException.class);
        Map<String, Object> row = jdbc.queryForMap("select * from account where id = ?", id);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("username")).isEqualTo("~deleted-" + id);
        assertThat(row.get("first_name")).isNull();
    }

    private void lockRow(AccountId id) {
        entityManager.find(Account.class, id, LockModeType.PESSIMISTIC_WRITE);
    }

    private static void runAsync(CompletableFuture<Void> result, Runnable action) {
        CompletableFuture.runAsync(action).whenComplete((ok, error) -> {
            if (error != null) result.completeExceptionally(error.getCause() != null ? error.getCause() : error);
            else result.complete(null);
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long createdId(String name, String role) {
        String json = """
                {"username": "%s", "firstName": "Jane", "lastName": "Doe", "password": "%s", "role": "%s"}
                """.formatted(name, PASSWORD, role);
        return ((Number) admin.post().uri("/api/accounts").contentType(MediaType.APPLICATION_JSON).body(json)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id")).longValue();
    }

    private RestTestClient signedInAs(String name, String password) {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(name, password))
                .build();
    }
}
