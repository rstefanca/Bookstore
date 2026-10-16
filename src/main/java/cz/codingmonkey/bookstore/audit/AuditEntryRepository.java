package cz.codingmonkey.bookstore.audit;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.ordering.OrderId;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditEntryRepository extends JpaRepository<AuditEntry, AuditEntryId> {

    List<AuditEntry> findByBookIdOrderByOccurredAtDesc(BookId bookId);

    List<AuditEntry> findByAuthorIdOrderByOccurredAtDesc(AuthorId authorId);

    List<AuditEntry> findByOrderIdOrderByOccurredAtDesc(OrderId orderId);

    boolean existsByOrderIdAndAction(OrderId orderId, AuditEntry.Action action);

    Page<AuditEntry> findAllBy(Pageable page);

    Page<AuditEntry> findByBookId(BookId bookId, Pageable page);

    Page<AuditEntry> findByAuthorId(AuthorId authorId, Pageable page);

    Page<AuditEntry> findByOrderId(OrderId orderId, Pageable page);
}
