package cz.codingmonkey.bookstore.security;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface AccountRepository extends JpaRepository<Account, AccountId> {

    Optional<Account> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    Page<Account> findAllBy(Pageable page);

    /** {@code SELECT ... FOR UPDATE}: serializes changes that must keep at least one admin. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Account> findAndLockByRole(Role role);
}
