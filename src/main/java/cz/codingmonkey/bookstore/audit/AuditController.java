package cz.codingmonkey.bookstore.audit;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/audit")
class AuditController {

    /**
     * @param subject what the entry is about
     * @param actor   username of whoever made the change; {@code null} for changes without a signed-in user
     */
    record AuditEntryResponse(AuditEntryId id, String action, SubjectResponse subject, String actor,
            String description, Instant occurredAt) {

        static AuditEntryResponse from(AuditEntry entry) {
            return new AuditEntryResponse(entry.getId(), entry.getAction().name(),
                    SubjectResponse.from(entry.getSubject()), entry.getActor(), entry.getDescription(),
                    entry.getOccurredAt());
        }
    }

    /** @param type {@code BOOK}, {@code AUTHOR} or {@code ORDER} */
    record SubjectResponse(String type, long id) {

        static SubjectResponse from(AuditSubject subject) {
            return new SubjectResponse(subject.type().name(), subject.id());
        }
    }

    private final AuditEntryRepository repository;

    AuditController(AuditEntryRepository repository) {
        this.repository = repository;
    }

    /**
     * @param bookId   no longer supported; rejected so that old clients don't silently get the whole log
     * @param authorId no longer supported, see {@code bookId}
     * @param orderId  no longer supported, see {@code bookId}
     */
    @GetMapping
    PagedModel<AuditEntryResponse> list(@RequestParam(required = false) AuditSubject.Type subjectType,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String bookId,
            @RequestParam(required = false) String authorId,
            @RequestParam(required = false) String orderId,
            Pageable pageable) {
        if (bookId != null || authorId != null || orderId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use subjectType and subjectId");
        }
        if ((subjectType == null) != (subjectId == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filter by both subjectType and subjectId");
        }
        if (subjectId != null && subjectId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subjectId must be positive");
        }
        // Newest first (id breaks ties between entries recorded at the same instant); a sort parameter is ignored.
        var page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        Page<AuditEntry> entries = subjectType == null
                ? repository.findAllBy(page)
                : repository.findBySubject(new AuditSubject(subjectType, subjectId), page);
        return new PagedModel<>(entries.map(AuditEntryResponse::from));
    }
}
