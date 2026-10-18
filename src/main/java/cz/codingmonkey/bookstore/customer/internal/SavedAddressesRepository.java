package cz.codingmonkey.bookstore.customer.internal;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SavedAddressesRepository extends JpaRepository<SavedAddresses, String> {

    /**
     * {@code SELECT ... FOR NO KEY UPDATE}: concurrent changes of one customer's addresses are serialized, so
     * saving shipping and invoice at the same time can't lose one of them.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SavedAddresses> findAndLockByCustomer(String customer);

    /** Creates the empty row if the customer has none yet; safe against concurrent first saves. */
    @Modifying
    @Query(value = "INSERT INTO customer_address (customer) VALUES (:customer) ON CONFLICT DO NOTHING", nativeQuery = true)
    void insertIfAbsent(@Param("customer") String customer);
}
