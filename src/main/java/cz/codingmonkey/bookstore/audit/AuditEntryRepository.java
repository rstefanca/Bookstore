package cz.codingmonkey.bookstore.audit;

import cz.codingmonkey.bookstore.shared.AccountId;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditEntryRepository extends JpaRepository<AuditEntry, AuditEntryId> {

    boolean existsByActorId(AccountId actorId);

    Page<AuditEntry> findAllBy(Pageable page);

    Page<AuditEntry> findBySubject(AuditSubject subject, Pageable page);

    List<AuditEntry> findBySubjectOrderByOccurredAtDesc(AuditSubject subject);

    boolean existsBySubjectAndActionAndOccurredAt(AuditSubject subject, String action, Instant occurredAt);
}
