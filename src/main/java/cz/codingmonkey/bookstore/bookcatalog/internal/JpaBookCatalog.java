package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails.AuthorSummary;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails.GenreSummary;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookSearch;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaBookCatalog implements BookCatalog {

    private final BookRepository repository;
    private final BookService books;

    JpaBookCatalog(BookRepository repository, BookService books) {
        this.repository = repository;
        this.books = books;
    }

    @Override
    public Optional<BookDetails> findBook(BookId bookId) {
        try {
            return Optional.of(toDetails(books.findById(bookId))); // cached
        } catch (BookNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public Page<BookDetails> findBooks(BookSearch search, Pageable page) {
        return books.findAll(search, page).map(JpaBookCatalog::toDetails);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean existsAndLockAgainstDeletion(BookId bookId) {
        return repository.findIdAndKeyShareLock(bookId.value()).isPresent();
    }

    private static BookDetails toDetails(BookResponse book) {
        return new BookDetails(book.id(),
                book.title(),
                book.authors().stream().map(author -> new AuthorSummary(author.id(), author.name())).toList(),
                book.genres().stream().map(genre -> new GenreSummary(genre.id(), genre.name())).toList(),
                book.isbn(),
                book.publishedYear(),
                book.description());
    }
}
