package cz.codingmonkey.bookstore.bookcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Checks that V6 turns the former free-text {@code book.author} column into {@code author} rows linked to books.
 */
@Testcontainers
class AuthorMigrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void migratesAuthorNamesToAuthorEntities() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);

        flyway(dataSource, "5").migrate();
        jdbc.update("""
                INSERT INTO book (title, author, isbn) VALUES
                    ('Dune', 'Frank Herbert', '9780441013593'),
                    ('Children of Dune', ' Frank Herbert ', '9780441104024'),
                    ('Neuromancer', 'William Gibson', '9780441569595')
                """);

        flyway(dataSource, "6").migrate();

        assertThat(jdbc.queryForList("SELECT name FROM author ORDER BY name", String.class))
                .as("one author per distinct trimmed name")
                .containsExactly("Frank Herbert", "William Gibson");

        List<Map<String, Object>> links = jdbc.queryForList("""
                SELECT b.title, a.name, ba.position
                FROM book_author ba JOIN book b ON b.id = ba.book_id JOIN author a ON a.id = ba.author_id
                ORDER BY b.title
                """);
        assertThat(links).containsExactly(
                Map.of("title", "Children of Dune", "name", "Frank Herbert", "position", 0),
                Map.of("title", "Dune", "name", "Frank Herbert", "position", 0),
                Map.of("title", "Neuromancer", "name", "William Gibson", "position", 0));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_name = 'book' AND column_name = 'author'
                """, Integer.class)).as("old column dropped").isZero();
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        return Flyway.configure().dataSource(dataSource).target(target).load();
    }
}
