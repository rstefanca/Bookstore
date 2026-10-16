package cz.codingmonkey.bookstore.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V21 adds order entries to the audit log; entries recorded before it must still satisfy the new rule.
 */
@Testcontainers
class AuditOrderMigrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Test
    void existingEntriesSurviveTheMigration() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);

        Flyway.configure().dataSource(dataSource).target("20").load().migrate();
        jdbc.update("""
                INSERT INTO audit_entry (action, book_id, description, occurred_at)
                VALUES ('BOOK_CREATED', 7, 'Created "Dune"', now())
                """);

        Flyway.configure().dataSource(dataSource).target("21").load().migrate();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_entry WHERE book_id = 7 AND order_id IS NULL",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("21");
    }
}
