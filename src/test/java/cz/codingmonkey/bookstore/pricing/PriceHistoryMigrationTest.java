package cz.codingmonkey.bookstore.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Checks that V13 starts the price history of every book that already has a price.
 */
@Testcontainers
class PriceHistoryMigrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Test
    void existingPricesGetAnOpenPeriod() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);

        Flyway.configure().dataSource(dataSource).target("12").load().migrate();
        jdbc.update("INSERT INTO book_price (book_id, amount, currency, version) VALUES (1, 12.90, 'EUR', 0)");

        Flyway.configure().dataSource(dataSource).target("13").load().migrate();

        Map<String, Object> period = jdbc.queryForMap("""
                SELECT book_id, amount, currency, valid_from IS NOT NULL AS has_start, valid_until
                FROM book_price_period
                """);
        assertThat(period).containsEntry("book_id", 1L)
                .containsEntry("currency", "EUR")
                .containsEntry("has_start", true)
                .containsEntry("valid_until", null);
        assertThat(period.get("amount").toString()).isEqualTo("12.90");
    }
}
