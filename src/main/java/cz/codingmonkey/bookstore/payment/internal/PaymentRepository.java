package cz.codingmonkey.bookstore.payment.internal;

import cz.codingmonkey.bookstore.payment.PaymentReference;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface PaymentRepository extends JpaRepository<Payment, PaymentReference> {

    /** {@code SELECT ... FOR UPDATE}: two refund requests for the same payment run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findAndLockByReference(PaymentReference reference);
}
