package cz.codingmonkey.bookstore.ordering.internal;

import cz.codingmonkey.bookstore.shared.AccountId;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CartRepository extends JpaRepository<Cart, AccountId> {

    /**
     * {@code SELECT ... FOR NO KEY UPDATE}: a customer's concurrent change of the cart waits, then fails its version
     * check, instead of the background step that holds the lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Cart> findAndLockByCustomer(AccountId customer);

    /**
     * A bulk delete (no version check, so a concurrent cart change can't fail it); the lines go with the cart
     * through {@code ON DELETE CASCADE}.
     *
     * @return the number of carts removed (0 or 1)
     */
    @Modifying
    @Query(value = "DELETE FROM cart WHERE customer_id = :customer", nativeQuery = true)
    int deleteByCustomerId(@Param("customer") long customer);
}
