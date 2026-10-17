package cz.codingmonkey.bookstore.inventory.internal;

import cz.codingmonkey.bookstore.inventory.ReservationId;
import cz.codingmonkey.bookstore.inventory.ReservationIdType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.Type;
import org.jspecify.annotations.Nullable;

/**
 * Stock taken for one reservation, or a record that it couldn't be (so a repeated request gets the same answer).
 * A reserved reservation holds the lines taken; a failed one holds the lines that lacked stock.
 */
@Entity
class StockReservation {

    enum Status { RESERVED, FAILED, RELEASED }

    @Id
    @Type(ReservationIdType.class)
    private ReservationId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 500)
    private @Nullable String reason;

    @Column(nullable = false)
    private Instant createdAt;

    @ElementCollection
    @CollectionTable(name = "stock_reservation_line", joinColumns = @JoinColumn(name = "reservation_id"))
    private List<ReservationLine> lines = new ArrayList<>();

    protected StockReservation() {
    }

    private StockReservation(ReservationId id, Status status, @Nullable String reason, List<ReservationLine> lines) {
        this.id = id;
        this.status = status;
        this.reason = reason;
        this.createdAt = Instant.now();
        this.lines.addAll(lines);
    }

    static StockReservation reserved(ReservationId id, List<ReservationLine> lines) {
        return new StockReservation(id, Status.RESERVED, null, lines);
    }

    static StockReservation failed(ReservationId id, String reason, List<ReservationLine> missingLines) {
        return new StockReservation(id, Status.FAILED, reason, missingLines);
    }

    ReservationId getId() { return id; }
    Status getStatus() { return status; }
    @Nullable String getReason() { return reason; }
    List<ReservationLine> getLines() { return List.copyOf(lines); }

    /** @return whether the stock should now be put back (only the first time) */
    boolean release() {
        if (status != Status.RESERVED) {
            return false;
        }
        status = Status.RELEASED;
        return true;
    }
}
