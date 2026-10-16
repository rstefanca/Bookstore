package cz.codingmonkey.bookstore.inventory.internal;

import cz.codingmonkey.bookstore.inventory.ReservationId;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface StockReservationRepository extends JpaRepository<StockReservation, ReservationId> {

    /** {@code SELECT ... FOR UPDATE}: two release requests for the same reservation run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<StockReservation> findAndLockById(ReservationId id);
}
