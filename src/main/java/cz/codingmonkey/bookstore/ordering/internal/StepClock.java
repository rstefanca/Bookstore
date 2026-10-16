package cz.codingmonkey.bookstore.ordering.internal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Times for order steps. Each time is later than the one before, even for steps published together (charged, then
 * confirmed), so listeners that handle the steps in parallel can still put them in order. Microseconds, as the
 * database keeps them.
 */
final class StepClock {

    private static final AtomicReference<Instant> last = new AtomicReference<>(Instant.EPOCH);

    private StepClock() {
    }

    static Instant next() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return last.updateAndGet(previous -> now.isAfter(previous) ? now : previous.plus(1, ChronoUnit.MICROS));
    }
}
