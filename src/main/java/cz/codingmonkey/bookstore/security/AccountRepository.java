package cz.codingmonkey.bookstore.security;

import cz.codingmonkey.bookstore.shared.AccountId;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Soft-deleted accounts (those with {@code deletedAt}) count as nonexistent for every lookup but {@code findById}. */
interface AccountRepository extends JpaRepository<Account, AccountId> {

    Optional<Account> findByUsernameIgnoreCaseAndDeletedAtIsNull(String username);

    boolean existsByUsernameIgnoreCaseAndDeletedAtIsNull(String username);

    long countByDeletedAtIsNull();

    Page<Account> findAllByDeletedAtIsNull(Pageable page);

    List<Account> findAllByIdInAndDeletedAtIsNull(Collection<AccountId> ids);

    /** {@code SELECT ... FOR UPDATE}: serializes changes that must keep at least one admin. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Account> findAndLockByRoleAndDeletedAtIsNull(Role role);
}
