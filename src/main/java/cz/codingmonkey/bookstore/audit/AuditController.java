package cz.codingmonkey.bookstore.audit;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.ordering.OrderId;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;
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
     * @param bookId   set for book entries, otherwise {@code null}
     * @param authorId set for author entries, otherwise {@code null}
     * @param actor    username of whoever made the change; {@code null} for changes without a signed-in user
     */
    record AuditEntryResponse(AuditEntryId id, String action, BookId bookId, AuthorId authorId, OrderId orderId,
            String actor, String description, Instant occurredAt) {

        static AuditEntryResponse from(AuditEntry entry) {
            return new AuditEntryResponse(entry.getId(), entry.getAction().name(), entry.getBookId(),
                    entry.getAuthorId(), entry.getOrderId(), entry.getActor(), entry.getDescription(),
                    entry.getOccurredAt());
        }
    }

    private final AuditEntryRepository repository;

    AuditController(AuditEntryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    PagedModel<AuditEntryResponse> list(@RequestParam(required = false) BookId bookId,
            @RequestParam(required = false) AuthorId authorId,
            @RequestParam(required = false) OrderId orderId,
            Pageable pageable) {
        if (Stream.of(bookId, authorId, orderId).filter(Objects::nonNull).count() > 1) {
            // An entry is about exactly one subject, so two filters together could never match.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filter by one of bookId, authorId or orderId");
        }
        // Newest first (id breaks ties between entries recorded at the same instant); a sort parameter is ignored.
        var page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        Page<AuditEntry> entries;
        if (bookId != null) {
            entries = repository.findByBookId(bookId, page);
        } else if (authorId != null) {
            entries = repository.findByAuthorId(authorId, page);
        } else if (orderId != null) {
            entries = repository.findByOrderId(orderId, page);
        } else {
            entries = repository.findAllBy(page);
        }
        return new PagedModel<>(entries.map(AuditEntryResponse::from));
    }
}
