package cz.codingmonkey.bookstore.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
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
 * V30 replaces the rater's username in {@code book_rating} with the account id.
 */
@Testcontainers
class RatingMigrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate migratedTo29() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).target("29").load().migrate();
        return jdbc;
    }

    private void migrateTo30(JdbcTemplate jdbc) {
        Flyway.configure().dataSource(jdbc.getDataSource()).target("30").load().migrate();
    }

    private long insertAccount(JdbcTemplate jdbc, String username) {
        return jdbc.queryForObject("""
                INSERT INTO account (username, password_hash, role) VALUES (?, '{noop}x', 'CUSTOMER') RETURNING id
                """, Long.class, username);
    }

    private void insertRating(JdbcTemplate jdbc, long book, String customer, int stars) {
        jdbc.update("INSERT INTO book_rating (book_id, customer, stars, rated_at) VALUES (?, ?, ?, now())", book,
                customer, stars);
    }

    @Test
    void ratingsMoveToTheAccountId() {
        JdbcTemplate jdbc = migratedTo29();
        long alice = insertAccount(jdbc, "Alice");
        long bob = insertAccount(jdbc, "bob");
        insertRating(jdbc, 1, "alice", 4);
        insertRating(jdbc, 1, "bob", 2);

        migrateTo30(jdbc);

        assertThat(jdbc.queryForList("SELECT customer_id || ':' || stars FROM book_rating ORDER BY stars",
                String.class)).isEqualTo(List.of(bob + ":2", alice + ":4"));
    }

    @Test
    void ratingsOfGoneUsersGetAPlaceholderAccount() {
        JdbcTemplate jdbc = migratedTo29();
        insertRating(jdbc, 1, "gone", 5);
        insertRating(jdbc, 2, "gone", 3);

        migrateTo30(jdbc);

        assertThat(jdbc.queryForObject("SELECT count(DISTINCT customer_id) FROM book_rating", Integer.class))
                .isEqualTo(1);
        Map<String, Object> account = jdbc.queryForMap("""
                SELECT a.id, a.username, a.role, a.deleted_at IS NOT NULL AS deleted, a.password_hash
                FROM account a JOIN book_rating r ON r.customer_id = a.id LIMIT 1
                """);
        assertThat(account.get("username")).isEqualTo("~deleted-" + account.get("id"));
        assertThat(account.get("role")).isEqualTo("CUSTOMER");
        assertThat(account.get("deleted")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM book_rating", Integer.class)).isEqualTo(2);
    }

    @Test
    void caseVariantDuplicatesCollapseToTheNewerRating() {
        JdbcTemplate jdbc = migratedTo29();
        long bob = insertAccount(jdbc, "bob");
        jdbc.update("INSERT INTO book_rating (book_id, customer, stars, rated_at) VALUES "
                + "(1, 'Gone', 1, now() - interval '2 days'), (1, 'gone', 5, now() - interval '1 day'), "
                + "(2, 'bob', 2, now() - interval '2 days'), (2, 'Bob', 4, now() - interval '1 day')");

        migrateTo30(jdbc);

        assertThat(jdbc.queryForList("SELECT book_id || ':' || stars FROM book_rating ORDER BY book_id",
                String.class)).isEqualTo(List.of("1:5", "2:4"));
        assertThat(jdbc.queryForObject("SELECT customer_id FROM book_rating WHERE book_id = 2", Long.class))
                .isEqualTo(bob);
    }

    @Test
    void primaryKeyStillRejectsADuplicate() {
        JdbcTemplate jdbc = migratedTo29();
        long alice = insertAccount(jdbc, "alice");
        insertRating(jdbc, 1, "alice", 4);

        migrateTo30(jdbc);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO book_rating (book_id, customer_id, stars, rated_at) VALUES (1, ?, 5, now())", alice))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
