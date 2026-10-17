package cz.codingmonkey.bookstore.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V23 replaces the book, author and order id columns with a subject type and id; entries recorded before it keep
 * their subject.
 */
@Testcontainers
class AuditSubjectMigrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Test
    void existingEntriesKeepTheirSubject() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);

        Flyway.configure().dataSource(dataSource).target("22").load().migrate();
        jdbc.update("""
                INSERT INTO audit_entry (action, book_id, description, occurred_at)
                VALUES ('BOOK_CREATED', 7, 'Created "Dune"', now())
                """);
        jdbc.update("""
                INSERT INTO audit_entry (action, author_id, description, occurred_at)
                VALUES ('AUTHOR_CREATED', 8, 'Created author', now())
                """);
        jdbc.update("""
                INSERT INTO audit_entry (action, order_id, description, occurred_at)
                VALUES ('ORDER_PLACED', 9, '1 copies', now())
                """);

        Flyway.configure().dataSource(dataSource).target("23").load().migrate();

        assertThat(jdbc.queryForList("SELECT subject_type || ':' || subject_id FROM audit_entry ORDER BY id",
                String.class)).isEqualTo(List.of("BOOK:7", "AUTHOR:8", "ORDER:9"));
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("23");
    }
}
