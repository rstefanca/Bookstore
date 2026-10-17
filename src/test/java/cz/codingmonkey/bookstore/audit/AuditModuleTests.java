package cz.codingmonkey.bookstore.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.shared.Auditable;
import cz.codingmonkey.bookstore.shared.LongId;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

/**
 * Bootstraps only the audit module and drives it with test-only {@link Auditable} events about a test-only id, so
 * it knows nothing of the modules that publish the real ones.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class AuditModuleTests {

    /** Typed like the real one: {@code typeName()} is "ORDER". */
    record OrderId(long value) implements LongId { }

    record AVeryLongSubjectNameThatGoesBeyondTheFiftyCharacterColumnLimitId(long value) implements LongId { }

    record Audited(String auditAction, LongId auditSubject, String auditDescription, String actor,
            Instant occurredAt) implements Auditable { }

    @Autowired
    AuditEntryRepository repository;

    @Test
    void recordsAnySubject(Scenario scenario) {
        var subject = new AuditSubject("ORDER", 48);
        Instant declinedAt = Instant.parse("2026-10-16T08:00:00.123456Z");
        scenario.publish(new Audited("ORDER_PAYMENT_DECLINED", new OrderId(48), "Declined", "rita", declinedAt))
                .andWaitForStateChange(() -> repository.findBySubjectOrderByOccurredAtDesc(subject),
                        list -> !list.isEmpty())
                .andVerify(entries -> assertThat(entries).singleElement().satisfies(entry -> {
                    assertThat(entry.getAction()).isEqualTo("ORDER_PAYMENT_DECLINED");
                    assertThat(entry.getSubject()).isEqualTo(new AuditSubject("ORDER", 48));
                    assertThat(entry.getActor()).isEqualTo("rita");
                    assertThat(entry.getDescription()).isEqualTo("Declined");
                    assertThat(entry.getOccurredAt()).as("when it happened, not when it was recorded")
                            .isEqualTo(declinedAt);
                }));
    }

    @Test
    void recordsChangesWithoutActor(Scenario scenario) {
        var subject = new AuditSubject("ORDER", 49);
        scenario.publish(new Audited("ORDER_PLACED", new OrderId(49), "1 copies", null, Instant.now()))
                .andWaitForStateChange(() -> repository.findBySubjectOrderByOccurredAtDesc(subject),
                        list -> !list.isEmpty())
                .andVerify(entries -> assertThat(entries).singleElement()
                        .extracting(AuditEntry::getActor).isNull());
    }

    @Test
    void theSameEventDeliveredAgainIsRecordedOnce(Scenario scenario) {
        // Events are delivered again after a crash between the listener's commit and marking the event done.
        var subject = new AuditSubject("ORDER", 50);
        var event = new Audited("ORDER_PLACED", new OrderId(50), "1 copies", "rita",
                Instant.parse("2026-10-16T08:00:00.123456789Z"));
        scenario.publish(event)
                .andWaitForStateChange(() -> repository.findBySubjectOrderByOccurredAtDesc(subject),
                        list -> !list.isEmpty());

        scenario.publish(event).andWaitForStateChange(() -> true);

        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(repository.findBySubjectOrderByOccurredAtDesc(subject)).singleElement()
                        .extracting(AuditEntry::getOccurredAt)
                        .isEqualTo(Instant.parse("2026-10-16T08:00:00.123456Z")));
    }

    @Test
    void theSameActionAtAnotherTimeIsRecordedAgain(Scenario scenario) {
        var subject = new AuditSubject("ORDER", 53);
        Instant first = Instant.parse("2026-10-16T09:00:00Z");
        scenario.publish(new Audited("ORDER_PLACED", new OrderId(53), "1 copies", "rita", first))
                .andWaitForStateChange(() -> repository.findBySubjectOrderByOccurredAtDesc(subject),
                        list -> list.size() == 1);

        scenario.publish(new Audited("ORDER_PLACED", new OrderId(53), "1 copies", "rita", first.plusSeconds(1)))
                .andWaitForStateChange(() -> repository.findBySubjectOrderByOccurredAtDesc(subject),
                        list -> list.size() == 2)
                .andVerify(entries -> assertThat(entries).hasSize(2));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logsOnlyActionAndSubject(Scenario scenario, CapturedOutput output) {
        var subject = new AuditSubject("ORDER", 52);
        scenario.publish(new Audited("ORDER_STOCK_MISSING", new OrderId(52), "Not enough stock: Sapiens",
                        "rita@example.com", Instant.now()))
                .andWaitForStateChange(() -> repository.findBySubjectOrderByOccurredAtDesc(subject),
                        list -> !list.isEmpty());

        await().untilAsserted(() -> assertThat(output.getAll())
                .contains("Recorded audit entry ORDER_STOCK_MISSING for order 52"));
        assertThat(output.getAll()).doesNotContain("rita@example.com", "Sapiens");
    }

    @Test
    void actionFormat() {
        for (String invalid : new String[] {"book_created", "1BOOK", "BOOK-CREATED", "", "A".repeat(51)}) {
            assertThatThrownBy(() -> AuditEntry.of(invalid, new OrderId(1), null, "x", Instant.now()))
                    .as("action '%s'", invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> AuditEntry.of("A".repeat(50), new OrderId(1), null, "x", Instant.now()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AuditEntry.of("ORDER_PLACED", null, null, "x", Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("subject");
    }

    @Test
    void subjectTypeFormat() {
        LongId anonymous = new LongId() {
            @Override
            public long value() {
                return 1;
            }
        };
        assertThatThrownBy(() -> AuditEntry.of("ORDER_PLACED", anonymous, null, "x", Instant.now()))
                .as("anonymous id, type name ''")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject type");
        var tooLong = new AVeryLongSubjectNameThatGoesBeyondTheFiftyCharacterColumnLimitId(1);
        assertThatThrownBy(() -> AuditEntry.of("ORDER_PLACED", tooLong, null, "x", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(tooLong.typeName());
        assertThatCode(() -> AuditEntry.of("ORDER_PLACED", new OrderId(1), null, "x", Instant.now()))
                .doesNotThrowAnyException();
    }

    @Test
    void subjectKeepsItsTypeWhenWrappedAgain() {
        assertThat(AuditSubject.of(new AuditSubject("BOOK", 2))).isEqualTo(new AuditSubject("BOOK", 2));
        assertThat(new AuditSubject("BOOK", 2)).hasToString("book 2");
    }
}
