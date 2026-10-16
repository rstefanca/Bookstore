package cz.codingmonkey.bookstore.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.inventory.ReservationId;
import cz.codingmonkey.bookstore.inventory.StockAdjustedEvent;
import cz.codingmonkey.bookstore.inventory.StockReleaseRequested;
import cz.codingmonkey.bookstore.inventory.StockReleased;
import cz.codingmonkey.bookstore.inventory.StockReservationFailed;
import cz.codingmonkey.bookstore.inventory.StockReservationRequested;
import cz.codingmonkey.bookstore.inventory.StockReservationRequested.Line;
import cz.codingmonkey.bookstore.inventory.StockReserved;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Only the inventory module runs; the test asks for reservations the way the ordering module does.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class StockReservationModuleTests {

    /** Unique ids per test, since tests share the database. */
    static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @MockitoBean
    BookCatalog books;

    @Autowired
    InventoryService inventory;

    @Autowired
    ApplicationEventPublisher publisher;

    BookId dune;
    BookId foundation;

    @BeforeEach
    void stock() {
        given(books.existsAndLockAgainstDeletion(any())).willReturn(true);
        dune = new BookId(IDS.incrementAndGet());
        foundation = new BookId(IDS.incrementAndGet());
        inventory.restock(dune, 3);
        inventory.restock(foundation, 1);
    }

    @Test
    void reservesAllLines(Scenario scenario) {
        ReservationId id = newReservation();

        scenario.publish(new StockReservationRequested(id, List.of(new Line(dune, 2), new Line(foundation, 1)), "rita"))
                .andWaitForEventOfType(StockReserved.class)
                .matching(reserved -> reserved.reservationId().equals(id))
                .toArrive();

        assertThat(quantity(dune)).isEqualTo(1);
        assertThat(quantity(foundation)).isZero();
    }

    @Test
    void reservesNothingIfAnyLineLacksStock(Scenario scenario) {
        ReservationId id = newReservation();

        scenario.publish(new StockReservationRequested(id, List.of(new Line(dune, 2), new Line(foundation, 2)), "rita"))
                .andWaitForEventOfType(StockReservationFailed.class)
                .matching(failed -> failed.reservationId().equals(id))
                .toArriveAndVerify(failed -> assertThat(failed.missingBooks()).containsExactly(foundation));

        assertThat(quantity(dune)).as("untouched").isEqualTo(3);
        assertThat(quantity(foundation)).isEqualTo(1);
    }

    @Test
    void booksWithoutStockRecordLackStock(Scenario scenario) {
        ReservationId id = newReservation();
        BookId neverStocked = new BookId(IDS.incrementAndGet());

        scenario.publish(new StockReservationRequested(id, List.of(new Line(neverStocked, 1)), "rita"))
                .andWaitForEventOfType(StockReservationFailed.class)
                .matching(failed -> failed.reservationId().equals(id))
                .toArriveAndVerify(failed -> assertThat(failed.missingBooks()).containsExactly(neverStocked));
    }

    @Test
    void repeatedRequestsGetTheSameAnswerWithoutTakingStockTwice(PublishedEvents events, Scenario scenario) {
        ReservationId id = newReservation();
        var request = new StockReservationRequested(id, List.of(new Line(dune, 2)), "rita");
        reserve(scenario, request);

        scenario.publish(request)
                .andWaitForStateChange(() -> answers(events, id) == 2 ? true : null)
                .andVerify(done -> assertThat(quantity(dune)).as("taken once").isEqualTo(1));
    }

    @Test
    void releasingPutsStockBackOnce(Scenario scenario) {
        ReservationId id = newReservation();
        reserve(scenario, new StockReservationRequested(id, List.of(new Line(dune, 2)), "rita"));

        // Asked twice at once (e.g. a retry): the stock comes back only once.
        scenario.stimulate(() -> {
                    publisher.publishEvent(new StockReleaseRequested(id, "rita"));
                    publisher.publishEvent(new StockReleaseRequested(id, "rita"));
                })
                .andWaitForStateChange(() -> quantity(dune), quantity -> quantity == 3)
                .andVerify(quantity -> assertThat(quantity).isEqualTo(3));
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(quantity(dune)).as("released once").isEqualTo(3));
    }

    @Test
    void releaseIsAnnouncedOnce(PublishedEvents events, Scenario scenario) {
        ReservationId id = newReservation();
        reserve(scenario, new StockReservationRequested(id, List.of(new Line(dune, 2)), "rita"));

        scenario.stimulate(() -> {
                    publisher.publishEvent(new StockReleaseRequested(id, "rita"));
                    publisher.publishEvent(new StockReleaseRequested(id, "rita"));
                })
                .andWaitForStateChange(() -> quantity(dune), quantity -> quantity == 3)
                .andVerify(quantity -> { });

        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(events.ofType(StockReleased.class).matching(r -> r.reservationId().equals(id))).hasSize(1));
    }

    @Test
    void stockChangesNameWhoCausedThem(Scenario scenario) {
        ReservationId id = newReservation();

        scenario.publish(new StockReservationRequested(id, List.of(new Line(dune, 1)), "rita"))
                .andWaitForEventOfType(StockAdjustedEvent.class)
                .matching(adjusted -> adjusted.bookId().equals(dune))
                .toArriveAndVerify(adjusted -> {
                    assertThat(adjusted.delta()).isEqualTo(-1);
                    assertThat(adjusted.actor()).isEqualTo("rita");
                });
    }

    @Test
    void twoReservationsForTheLastCopyGetItOnlyOnce(PublishedEvents events, Scenario scenario) {
        ReservationId first = newReservation();
        ReservationId second = newReservation();

        // Published together, so both are handled at the same time after the transaction commits.
        scenario.stimulate(() -> {
                    var lastCopy = List.of(new Line(foundation, 1));
                    publisher.publishEvent(new StockReservationRequested(first, lastCopy, "rita"));
                    publisher.publishEvent(new StockReservationRequested(second, lastCopy, "sam"));
                })
                .andWaitForStateChange(() -> answers(events, first) + answers(events, second) == 2 ? true : null)
                .andVerify(done -> { });

        long reserved = count(events.ofType(StockReserved.class)
                .matching(r -> r.reservationId().equals(first) || r.reservationId().equals(second)));
        assertThat(reserved).isEqualTo(1);
        assertThat(quantity(foundation)).isZero();
    }

    private void reserve(Scenario scenario, StockReservationRequested request) {
        scenario.publish(request)
                .andWaitForEventOfType(StockReserved.class)
                .matching(reserved -> reserved.reservationId().equals(request.reservationId()))
                .toArrive();
    }

    private long answers(PublishedEvents events, ReservationId id) {
        return count(events.ofType(StockReserved.class).matching(r -> r.reservationId().equals(id)))
                + count(events.ofType(StockReservationFailed.class).matching(f -> f.reservationId().equals(id)));
    }

    private static long count(Iterable<?> events) {
        return StreamSupport.stream(events.spliterator(), false).count();
    }

    private int quantity(BookId bookId) {
        return inventory.findByBookId(bookId).quantity();
    }

    private static ReservationId newReservation() {
        return new ReservationId(IDS.incrementAndGet());
    }
}
