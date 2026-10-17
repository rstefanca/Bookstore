package cz.codingmonkey.bookstore.audit;

import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/audit")
class AuditController {

    private static final Pattern TYPE_FORMAT = Pattern.compile("[A-Z][A-Z0-9_]*");

    /** @param actor username of whoever made the change; {@code null} for changes without a signed-in user */
    record AuditEntryResponse(AuditEntryId id, String action, SubjectResponse subject, String actor,
            String description, Instant occurredAt) {

        static AuditEntryResponse from(AuditEntry entry) {
            return new AuditEntryResponse(entry.getId(), entry.getAction(),
                    new SubjectResponse(entry.getSubject().typeName(), entry.getSubject().value()),
                    entry.getActor(), entry.getDescription(), entry.getOccurredAt());
        }
    }

    /** What the entry is about, e.g. {@code {"type": "BOOK", "id": 2}}. */
    record SubjectResponse(String type, long id) {
    }

    private final AuditEntryRepository repository;

    AuditController(AuditEntryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    PagedModel<AuditEntryResponse> list(@RequestParam(required = false) String subjectType,
            @RequestParam(required = false) Long subjectId,
            @RequestParam MultiValueMap<String, String> parameters,
            Pageable pageable) {
        if (parameters.containsKey("bookId") || parameters.containsKey("authorId")
                || parameters.containsKey("orderId")) {
            // Unknown parameters are ignored, so an old client would silently get the whole log.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use subjectType and subjectId");
        }
        if ((subjectType == null) != (subjectId == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filter by both subjectType and subjectId");
        }
        if (subjectType != null && !TYPE_FORMAT.matcher(subjectType).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subjectType must be upper snake case");
        }
        if (subjectId != null && subjectId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subjectId must be positive");
        }
        // Newest first (id breaks ties between entries recorded at the same instant); a sort parameter is ignored.
        var page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        Page<AuditEntry> entries = subjectType != null
                ? repository.findBySubject(new AuditSubject(subjectType, subjectId), page)
                : repository.findAllBy(page);
        return new PagedModel<>(entries.map(AuditEntryResponse::from));
    }
}
