package cz.codingmonkey.bookstore.audit;

import cz.codingmonkey.bookstore.security.Accounts;
import cz.codingmonkey.bookstore.shared.AccountId;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
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
@Tag(name = "Audit", description = """
        Who changed what: an entry for every change to books, authors, stock and prices, and for every step of an \
        order. Admins only (`ADMIN`).""")
class AuditController {

    /**
     * A recorded change.
     *
     * @param action      what happened, in upper snake case, e.g. {@code BOOK_CREATED}, {@code PRICE_CHANGED} or
     *                    {@code ORDER_CONFIRMED}
     * @param actor       username of whoever made the change, {@code "(deleted user)"} if their account was deleted;
     *                    {@code null} for changes without a signed-in user
     * @param actorId     account id of whoever made the change; {@code null} for changes without a signed-in user
     * @param description the change in words, e.g. "Price changed from 10.00 EUR to 8.50 EUR"
     */
    record AuditEntryResponse(AuditEntryId id, String action, SubjectResponse subject, @Nullable String actor,
            @Nullable AccountId actorId, String description, Instant occurredAt) {

        /** @param usernames the usernames of the entry's actor, from {@link Accounts#usernamesOf} */
        static AuditEntryResponse from(AuditEntry entry, Map<AccountId, String> usernames) {
            AccountId actorId = entry.getActorId();
            return new AuditEntryResponse(entry.getId(), entry.getAction(),
                    new SubjectResponse(entry.getSubject().typeName(), entry.getSubject().value()),
                    actorId == null ? null : usernames.get(actorId), actorId, entry.getDescription(),
                    entry.getOccurredAt());
        }
    }

    /** What the entry is about, e.g. {@code {"type": "BOOK", "id": 2}}. */
    record SubjectResponse(String type, long id) {
    }

    private final AuditEntryRepository repository;
    private final Accounts accounts;

    AuditController(AuditEntryRepository repository, Accounts accounts) {
        this.repository = repository;
        this.accounts = accounts;
    }

    /**
     * Lists audit entries, newest first.
     * <p>
     * To see one subject's history, give both {@code subjectType} and {@code subjectId}, e.g.
     * {@code ?subjectType=ORDER&subjectId=7} for every step of one purchase.
     *
     * @param subjectType the kind of subject, in upper snake case, e.g. {@code BOOK}, {@code AUTHOR} or {@code ORDER}
     * @param subjectId   the subject's id
     */
    @GetMapping
    @ApiResponse(responseCode = "200", description = "A page of audit entries")
    @ApiResponse(responseCode = "400", description = "Only one of subjectType and subjectId, or an invalid value")
    PagedModel<AuditEntryResponse> list(@RequestParam(required = false) @Nullable String subjectType,
            @RequestParam(required = false) @Nullable Long subjectId,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters,
            Pageable pageable) {
        if (parameters.containsKey("bookId") || parameters.containsKey("authorId")
                || parameters.containsKey("orderId")) {
            // Unknown parameters are ignored, so an old client would silently get the whole log.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use subjectType and subjectId");
        }
        if ((subjectType == null) != (subjectId == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filter by both subjectType and subjectId");
        }
        if (subjectType != null && !AuditEntry.isValidName(subjectType)) {
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
        // One lookup for the whole page.
        Map<AccountId, String> usernames = accounts.usernamesOf(
                entries.stream().map(AuditEntry::getActorId).filter(Objects::nonNull).distinct().toList());
        return new PagedModel<>(entries.map(entry -> AuditEntryResponse.from(entry, usernames)));
    }
}
