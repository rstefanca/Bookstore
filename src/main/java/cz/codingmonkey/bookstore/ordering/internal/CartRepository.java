package cz.codingmonkey.bookstore.ordering.internal;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface CartRepository extends JpaRepository<Cart, String> {

    /**
     * {@code SELECT ... FOR NO KEY UPDATE}: a customer's concurrent change of the cart waits, then fails its version
     * check, instead of the background step that holds the lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Cart> findAndLockByCustomer(String customer);
}
