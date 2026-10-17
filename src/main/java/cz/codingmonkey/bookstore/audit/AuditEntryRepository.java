package cz.codingmonkey.bookstore.audit;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditEntryRepository extends JpaRepository<AuditEntry, AuditEntryId> {

    List<AuditEntry> findBySubjectOrderByOccurredAtDesc(AuditSubject subject);

    boolean existsBySubjectAndAction(AuditSubject subject, AuditEntry.Action action);

    Page<AuditEntry> findAllBy(Pageable page);

    Page<AuditEntry> findBySubject(AuditSubject subject, Pageable page);
}
