package cz.codingmonkey.bookstore.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditEntryRepository extends JpaRepository<AuditEntry, AuditEntryId> {

    Page<AuditEntry> findAllBy(Pageable page);

    Page<AuditEntry> findBySubject(AuditSubject subject, Pageable page);

    List<AuditEntry> findBySubjectOrderByOccurredAtDesc(AuditSubject subject);

    boolean existsBySubjectAndActionAndOccurredAt(AuditSubject subject, String action, Instant occurredAt);
}
