package cz.codingmonkey.bookstore.audit;

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
 * V33 replaces the actor's username in {@code audit_entry} with the account id.
 */
@Testcontainers
class AuditActorMigrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate migratedTo32() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).target("32").load().migrate();
        return jdbc;
    }

    private void migrateTo33(JdbcTemplate jdbc) {
        Flyway.configure().dataSource(jdbc.getDataSource()).target("33").load().migrate();
    }

    private long insertAccount(JdbcTemplate jdbc, String username) {
        return jdbc.queryForObject("""
                INSERT INTO account (username, password_hash, role) VALUES (?, '{noop}x', 'STAFF') RETURNING id
                """, Long.class, username);
    }

    private long insertEntry(JdbcTemplate jdbc, String description, String actor) {
        return jdbc.queryForObject("""
                INSERT INTO audit_entry (action, subject_type, subject_id, description, actor, occurred_at)
                VALUES ('BOOK_CREATED', 'BOOK', 1, ?, ?, now()) RETURNING id
                """, Long.class, description, actor);
    }

    private Long actorIdOf(JdbcTemplate jdbc, long entry) {
        return jdbc.queryForObject("SELECT actor_id FROM audit_entry WHERE id = ?", Long.class, entry);
    }

    @Test
    void actorsMoveToTheAccountId() {
        JdbcTemplate jdbc = migratedTo32();
        long sam = insertAccount(jdbc, "Sam.Staff");
        long rita = insertAccount(jdbc, "rita");
        long bySam = insertEntry(jdbc, "by sam", "sam.staff");
        long byRita = insertEntry(jdbc, "by rita", "rita");

        migrateTo33(jdbc);

        assertThat(actorIdOf(jdbc, bySam)).isEqualTo(sam);
        assertThat(actorIdOf(jdbc, byRita)).isEqualTo(rita);
    }

    @Test
    void anEntryWithoutAnActorKeepsNoActor() {
        JdbcTemplate jdbc = migratedTo32();
        long startup = insertEntry(jdbc, "at startup", null);

        migrateTo33(jdbc);

        assertThat(actorIdOf(jdbc, startup)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Integer.class))
                .as("no placeholder for a missing actor").isZero();
    }

    @Test
    void anActorWithoutAnAccountGetsAPlaceholder() {
        JdbcTemplate jdbc = migratedTo32();
        long first = insertEntry(jdbc, "first", "gone");
        long second = insertEntry(jdbc, "second", "Gone");

        migrateTo33(jdbc);

        assertThat(actorIdOf(jdbc, first)).isNotNull().isEqualTo(actorIdOf(jdbc, second));
        Map<String, Object> account = jdbc.queryForMap("""
                SELECT id, username, role, deleted_at IS NOT NULL AS deleted, password_hash FROM account WHERE id = ?
                """, actorIdOf(jdbc, first));
        assertThat(account.get("username")).isEqualTo("~deleted-" + account.get("id"));
        assertThat(account.get("role")).isEqualTo("CUSTOMER");
        assertThat(account.get("deleted")).isEqualTo(true);
        assertThat((String) account.get("password_hash")).startsWith("{bcrypt}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_entry", Integer.class)).isEqualTo(2);
    }
}
