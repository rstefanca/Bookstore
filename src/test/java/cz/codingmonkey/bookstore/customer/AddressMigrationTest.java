package cz.codingmonkey.bookstore.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V31 replaces the customer's username in {@code customer_address} with the account id.
 */
@Testcontainers
class AddressMigrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate migratedTo30() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).target("30").load().migrate();
        return jdbc;
    }

    private void migrateTo31(JdbcTemplate jdbc) {
        Flyway.configure().dataSource(jdbc.getDataSource()).target("31").load().migrate();
    }

    private long insertAccount(JdbcTemplate jdbc, String username) {
        return jdbc.queryForObject("""
                INSERT INTO account (username, password_hash, role) VALUES (?, '{noop}x', 'CUSTOMER') RETURNING id
                """, Long.class, username);
    }

    private void insertAddress(JdbcTemplate jdbc, String customer, String shippingName) {
        jdbc.update("""
                INSERT INTO customer_address (customer, shipping_name, shipping_street, shipping_city,
                                              shipping_postal_code, shipping_country)
                VALUES (?, ?, 'Main 1', 'Brno', '602 00', 'CZ')
                """, customer, shippingName);
    }

    @Test
    void addressesMoveToTheAccountId() {
        JdbcTemplate jdbc = migratedTo30();
        long alice = insertAccount(jdbc, "Alice");
        long bob = insertAccount(jdbc, "bob");
        insertAddress(jdbc, "alice", "Alice A");
        insertAddress(jdbc, "bob", "Bob B");

        migrateTo31(jdbc);

        assertThat(jdbc.queryForList(
                "SELECT customer_id || ':' || shipping_name FROM customer_address ORDER BY shipping_name",
                String.class)).isEqualTo(List.of(alice + ":Alice A", bob + ":Bob B"));
    }

    @Test
    void addressesOfGoneUsersAreDeleted() {
        JdbcTemplate jdbc = migratedTo30();
        long bob = insertAccount(jdbc, "bob");
        insertAddress(jdbc, "gone", "Gone G");
        insertAddress(jdbc, "bob", "Bob B");

        migrateTo31(jdbc);

        assertThat(jdbc.queryForList("SELECT customer_id FROM customer_address", Long.class))
                .isEqualTo(List.of(bob));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Integer.class))
                .as("no placeholder accounts for addresses").isEqualTo(1);
    }

    @Test
    void caseVariantDuplicatesCollapseToOneRow() {
        JdbcTemplate jdbc = migratedTo30();
        long bob = insertAccount(jdbc, "bob");
        insertAddress(jdbc, "bob", "First");
        insertAddress(jdbc, "Bob", "Second");

        migrateTo31(jdbc);

        assertThat(jdbc.queryForList("SELECT customer_id || ':' || shipping_name FROM customer_address",
                String.class)).isEqualTo(List.of(bob + ":First"));
    }

    @Test
    void primaryKeyStillRejectsADuplicate() {
        JdbcTemplate jdbc = migratedTo30();
        long alice = insertAccount(jdbc, "alice");
        insertAddress(jdbc, "alice", "Alice A");

        migrateTo31(jdbc);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO customer_address (customer_id) VALUES (?)", alice))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
