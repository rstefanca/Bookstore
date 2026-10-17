package cz.codingmonkey.bookstore.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V23 replaces the book, author and order columns with one subject; existing entries keep what they are about.
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
                INSERT INTO audit_entry (action, book_id, author_id, order_id, description, occurred_at)
                VALUES ('BOOK_CREATED', 7, NULL, NULL, 'Created "Dune"', now()),
                       ('AUTHOR_CREATED', NULL, 8, NULL, 'Created author "Frank Herbert"', now()),
                       ('ORDER_PLACED', NULL, NULL, 9, '1 copies, 9.99 EUR', now())
                """);

        Flyway.configure().dataSource(dataSource).target("23").load().migrate();

        assertThat(subjectOf(jdbc, "BOOK_CREATED")).containsEntry("subject_type", "BOOK")
                .containsEntry("subject_id", 7L);
        assertThat(subjectOf(jdbc, "AUTHOR_CREATED")).containsEntry("subject_type", "AUTHOR")
                .containsEntry("subject_id", 8L);
        assertThat(subjectOf(jdbc, "ORDER_PLACED")).containsEntry("subject_type", "ORDER")
                .containsEntry("subject_id", 9L);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO audit_entry (action, subject_type, subject_id, description, occurred_at)
                VALUES ('ORDER_PLACED', 'ORDER', 9, 'again', now())
                """)).as("each order step is still recorded once")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static Map<String, Object> subjectOf(JdbcTemplate jdbc, String action) {
        return jdbc.queryForMap("SELECT subject_type, subject_id FROM audit_entry WHERE action = ?", action);
    }
}
