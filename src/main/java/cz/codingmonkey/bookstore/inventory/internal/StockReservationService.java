package cz.codingmonkey.bookstore.inventory.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.inventory.StockAdjustedEvent;
import cz.codingmonkey.bookstore.inventory.StockReleaseRequested;
import cz.codingmonkey.bookstore.inventory.StockReleased;
import cz.codingmonkey.bookstore.inventory.StockReservationFailed;
import cz.codingmonkey.bookstore.inventory.StockReservationRequested;
import cz.codingmonkey.bookstore.inventory.StockReserved;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Takes stock for reservations and gives it back. Knows nothing about orders: it answers
 * {@link StockReservationRequested} and {@link StockReleaseRequested} from whichever module asks.
 * <p>
 * Both handlers run asynchronously after the requester's transaction commits and may see the same request more
 * than once (retries). Reservations are stored, including failed ones, so a repeated request gets the earlier
 * answer instead of taking stock twice.
 */
@Service
class StockReservationService {

    private static final Logger log = LogManager.getLogger(StockReservationService.class);
    private static final ChangeLog changes = ChangeLog.of(StockReservationService.class);

    private final StockReservationRepository reservations;
    private final InventoryItemRepository items;
    private final ApplicationEventPublisher events;

    StockReservationService(StockReservationRepository reservations, InventoryItemRepository items,
            ApplicationEventPublisher events) {
        this.reservations = reservations;
        this.items = items;
        this.events = events;
    }

    /** All lines or none: the needed stock rows are locked, checked, then all withdrawn. */
    @ApplicationModuleListener
    void on(StockReservationRequested request) {
        var existing = reservations.findById(request.reservationId());
        if (existing.isPresent()) {
            log.debug("Reservation {} was already handled; repeating its answer", request.reservationId());
            answer(existing.get());
            return;
        }
        List<ReservationLine> lines = request.lines().stream()
                .map(line -> new ReservationLine(line.bookId(), line.quantity()))
                .toList();
        Map<BookId, InventoryItem> stock = items.findAndLockByBookIdInOrderByBookIdAsc(
                        lines.stream().map(ReservationLine::getBookId).toList()).stream()
                .collect(Collectors.toMap(InventoryItem::getBookId, Function.identity()));

        List<ReservationLine> missing = new ArrayList<>(lines.size());
        for (ReservationLine line : lines) {
            InventoryItem item = stock.get(line.getBookId());
            if (item == null || item.getQuantity() < line.getQuantity()) {
                missing.add(line);
            }
        }

        StockReservation reservation;
        if (missing.isEmpty()) {
            for (ReservationLine line : lines) {
                InventoryItem item = stock.get(line.getBookId());
                item.withdraw(line.getQuantity());
                events.publishEvent(new StockAdjustedEvent(line.getBookId(), -line.getQuantity(),
                        item.getQuantity(), request.actor()));
            }
            reservation = reservations.save(StockReservation.reserved(request.reservationId(), lines));
            changes.info("Reserved stock for reservation {} ({} lines)", reservation.getId(), lines.size());
        } else {
            String reason = "Not enough stock for books "
                    + missing.stream().map(line -> line.getBookId().toString()).collect(Collectors.joining(", "));
            reservation = reservations.save(StockReservation.failed(request.reservationId(), reason, missing));
            changes.info("Couldn't reserve stock for reservation {}: {}", reservation.getId(), reason);
        }
        answer(reservation);
    }

    /**
     * Puts the stock back, once: the reservation row is locked, so a repeated request running at the same time
     * waits and then finds it released. Stock of books deleted in the meantime can't be put back and is skipped.
     */
    @ApplicationModuleListener
    void on(StockReleaseRequested request) {
        var found = reservations.findAndLockById(request.reservationId());
        if (found.isEmpty() || !found.get().release()) {
            log.debug("Nothing to release for reservation {}", request.reservationId());
            return;
        }
        StockReservation reservation = found.get();
        Map<BookId, InventoryItem> stock = items.findAndLockByBookIdInOrderByBookIdAsc(
                        reservation.getLines().stream().map(ReservationLine::getBookId).toList()).stream()
                .collect(Collectors.toMap(InventoryItem::getBookId, Function.identity()));
        for (ReservationLine line : reservation.getLines()) {
            InventoryItem item = stock.get(line.getBookId());
            if (item == null) {
                log.warn("Book {} is gone; can't put back {} copies of reservation {}", line.getBookId(),
                        line.getQuantity(), reservation.getId());
                continue;
            }
            item.restock(line.getQuantity());
            events.publishEvent(new StockAdjustedEvent(line.getBookId(), line.getQuantity(), item.getQuantity(),
                    request.actor()));
        }
        events.publishEvent(new StockReleased(reservation.getId()));
        changes.info("Released stock of reservation {}", reservation.getId());
    }

    private void answer(StockReservation reservation) {
        switch (reservation.getStatus()) {
            case RESERVED -> events.publishEvent(new StockReserved(reservation.getId()));
            case FAILED -> events.publishEvent(new StockReservationFailed(reservation.getId(), reservation.getReason(),
                    reservation.getLines().stream().map(ReservationLine::getBookId).toList()));
            case RELEASED -> log.debug("Reservation {} was already released; not answering again", reservation.getId());
        }
    }
}
