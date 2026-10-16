package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookCondition;
import cz.codingmonkey.bookstore.bookcatalog.BookSearch;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filters for listing books. They combine with AND.
 */
final class BookSpecifications {

    private BookSpecifications() {
    }

    /** Combines the search's filters; unset ones are ignored. */
    static Specification<Book> matching(BookSearch search) {
        List<Specification<Book>> filters = new ArrayList<>();
        if (search.title() != null) {
            filters.add(hasTitleContaining(search.title()));
        }
        if (search.authorName() != null) {
            filters.add(hasAuthorNamed(search.authorName()));
        }
        if (search.authorId() != null) {
            filters.add(hasAuthor(search.authorId()));
        }
        if (search.genreId() != null) {
            filters.add(hasGenre(search.genreId()));
        }
        if (search.condition() != null) {
            filters.add(satisfies(search.condition()));
        }
        return filters.isEmpty() ? Specification.unrestricted() : Specification.allOf(filters);
    }

    static Specification<Book> hasTitleContaining(String part) {
        return (book, query, cb) -> cb.like(cb.lower(book.get("title")), "%" + part.toLowerCase() + "%");
    }

    static Specification<Book> satisfies(BookCondition condition) {
        return (book, query, cb) -> condition.toPredicate(book.get("id"), query, cb);
    }

    static Specification<Book> hasAuthor(AuthorId authorId) {
        return (book, query, cb) -> cb.isMember(authorId, book.get("authorIds"));
    }

    static Specification<Book> hasGenre(GenreId genreId) {
        return (book, query, cb) -> cb.isMember(genreId, book.get("genreIds"));
    }

    /** Any of the book's authors has a name containing {@code part}, ignoring case. */
    static Specification<Book> hasAuthorNamed(String part) {
        return (book, query, cb) -> {
            Subquery<Integer> matchingAuthor = query.subquery(Integer.class);
            Root<Author> author = matchingAuthor.from(Author.class);
            Expression<AuthorId> authorId = author.get("id");
            Expression<List<AuthorId>> bookAuthorIds = book.get("authorIds");
            matchingAuthor.select(cb.literal(1)).where(
                    cb.like(cb.lower(author.get("name")), "%" + part.toLowerCase() + "%"),
                    cb.isMember(authorId, bookAuthorIds));
            return cb.exists(matchingAuthor);
        };
    }
}
