package cz.codingmonkey.bookstore.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V32 replaces the customer's username in {@code cart}, {@code cart_line} and {@code customer_order} with the
 * account id.
 */
@Testcontainers
class OrderMigrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate migratedTo31() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).target("31").load().migrate();
        return jdbc;
    }

    private void migrateTo32(JdbcTemplate jdbc) {
        Flyway.configure().dataSource(jdbc.getDataSource()).target("32").load().migrate();
    }

    private long insertAccount(JdbcTemplate jdbc, String username) {
        return jdbc.queryForObject("""
                INSERT INTO account (username, password_hash, role) VALUES (?, '{noop}x', 'CUSTOMER') RETURNING id
                """, Long.class, username);
    }

    private long insertOrder(JdbcTemplate jdbc, String customer, String total) {
        return jdbc.queryForObject("""
                INSERT INTO customer_order (customer, status, total, currency, created_at, version)
                VALUES (?, 'CONFIRMED', ?::numeric, 'EUR', now(), 0) RETURNING id
                """, Long.class, customer, total);
    }

    private void insertCart(JdbcTemplate jdbc, String customer, int... bookAndQuantity) {
        jdbc.update("INSERT INTO cart (customer, version) VALUES (?, 0)", customer);
        for (int i = 0; i < bookAndQuantity.length; i += 2) {
            jdbc.update("INSERT INTO cart_line (customer, book_id, quantity) VALUES (?, ?, ?)", customer,
                    bookAndQuantity[i], bookAndQuantity[i + 1]);
        }
    }

    @Test
    void ordersMoveToTheAccountId() {
        JdbcTemplate jdbc = migratedTo31();
        long alice = insertAccount(jdbc, "Alice");
        long bob = insertAccount(jdbc, "bob");
        insertOrder(jdbc, "alice", "10.00");
        insertOrder(jdbc, "bob", "20.00");
        insertOrder(jdbc, "BOB", "30.00");

        migrateTo32(jdbc);

        assertThat(jdbc.queryForList("SELECT customer_id || ':' || total FROM customer_order ORDER BY total",
                String.class)).isEqualTo(List.of(alice + ":10.00", bob + ":20.00", bob + ":30.00"));
    }

    @Test
    void ordersOfGoneUsersGetAPlaceholderAccount() {
        JdbcTemplate jdbc = migratedTo31();
        insertOrder(jdbc, "gone", "10.00");
        insertOrder(jdbc, "Gone", "20.00");
        insertOrder(jdbc, "other-gone", "30.00");

        migrateTo32(jdbc);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT customer_id) FROM customer_order", Integer.class))
                .isEqualTo(2);
        Map<String, Object> account = jdbc.queryForMap("""
                SELECT a.id, a.username, a.role, a.deleted_at IS NOT NULL AS deleted, a.password_hash
                FROM account a JOIN customer_order o ON o.customer_id = a.id WHERE o.total = 10.00
                """);
        assertThat(account.get("username")).isEqualTo("~deleted-" + account.get("id"));
        assertThat(account.get("role")).isEqualTo("CUSTOMER");
        assertThat(account.get("deleted")).isEqualTo(true);
        assertThat((String) account.get("password_hash")).startsWith("{bcrypt}");
        assertThat(jdbc.queryForObject("SELECT customer_id FROM customer_order WHERE total = 20.00", Long.class))
                .isEqualTo(account.get("id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE username NOT LIKE '~deleted-%'",
                Integer.class)).isZero();
    }

    @Test
    void customerIdIsRequiredAndIndexedNewestFirst() {
        JdbcTemplate jdbc = migratedTo31();

        migrateTo32(jdbc);

        assertThat(jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'customer_order_customer_idx'", String.class))
                .contains("(customer_id, created_at DESC)");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO customer_order (status, total, currency, created_at, version)
                VALUES ('CONFIRMED', 1, 'EUR', now(), 0)
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cartsAndLinesMoveToTheAccountId() {
        JdbcTemplate jdbc = migratedTo31();
        long alice = insertAccount(jdbc, "Alice");
        long bob = insertAccount(jdbc, "bob");
        insertCart(jdbc, "alice", 1, 2, 2, 1);
        insertCart(jdbc, "bob", 3, 4);

        migrateTo32(jdbc);

        assertThat(jdbc.queryForList("SELECT customer_id FROM cart ORDER BY customer_id", Long.class))
                .isEqualTo(List.of(alice, bob));
        assertThat(jdbc.queryForList(
                "SELECT customer_id || ':' || book_id || 'x' || quantity FROM cart_line ORDER BY book_id",
                String.class)).isEqualTo(List.of(alice + ":1x2", alice + ":2x1", bob + ":3x4"));
    }

    @Test
    void cartsOfGoneUsersAreDeleted() {
        JdbcTemplate jdbc = migratedTo31();
        long bob = insertAccount(jdbc, "bob");
        insertCart(jdbc, "gone", 1, 2);
        insertCart(jdbc, "bob", 3, 4);

        migrateTo32(jdbc);

        assertThat(jdbc.queryForList("SELECT customer_id FROM cart", Long.class)).isEqualTo(List.of(bob));
        assertThat(jdbc.queryForList("SELECT book_id FROM cart_line", Long.class)).isEqualTo(List.of(3L));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Integer.class))
                .as("no placeholder accounts for carts").isEqualTo(1);
    }

    @Test
    void caseVariantCartsMergeIntoOneWithOneLinePerBook() {
        JdbcTemplate jdbc = migratedTo31();
        long bob = insertAccount(jdbc, "bob");
        insertCart(jdbc, "bob", 1, 2, 2, 1);
        insertCart(jdbc, "Bob", 2, 5, 3, 1);

        migrateTo32(jdbc);

        assertThat(jdbc.queryForList("SELECT customer_id FROM cart", Long.class)).isEqualTo(List.of(bob));
        assertThat(jdbc.queryForList(
                "SELECT customer_id || ':' || book_id || 'x' || quantity FROM cart_line ORDER BY book_id",
                String.class)).isEqualTo(List.of(bob + ":1x2", bob + ":2x1", bob + ":3x1"));
    }

    @Test
    void cartKeysAndCascadeStillHold() {
        JdbcTemplate jdbc = migratedTo31();
        long bob = insertAccount(jdbc, "bob");
        insertCart(jdbc, "bob", 1, 2);

        migrateTo32(jdbc);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO cart (customer_id, version) VALUES (?, 0)", bob))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cart_line (customer_id, book_id, quantity) VALUES (?, 1, 1)", bob))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cart_line (customer_id, book_id, quantity) VALUES (?, 1, 1)", bob + 1000))
                .as("a line needs its cart").isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM cart WHERE customer_id = ?", bob);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_line", Integer.class)).isZero();
    }
}
