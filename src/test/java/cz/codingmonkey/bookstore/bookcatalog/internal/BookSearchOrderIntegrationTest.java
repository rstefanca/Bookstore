package cz.codingmonkey.bookstore.bookcatalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookCatalog;
import cz.codingmonkey.bookstore.bookcatalog.BookCondition;
import cz.codingmonkey.bookstore.bookcatalog.BookDetails;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.BookOrder;
import cz.codingmonkey.bookstore.bookcatalog.BookSearch;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The extension points other modules use to sort ({@link BookOrder}) and filter ({@link BookCondition}) the catalog.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BookSearchOrderIntegrationTest {

    private static final String MARKER = "Extension Point Book";

    @Autowired
    BookCatalog catalog;

    @Autowired
    BookRepository books;

    @Autowired
    AuthorRepository authors;

    private BookId a;
    private BookId b;
    private BookId c;

    @BeforeEach
    void createThreeBooks() {
        books.deleteAll();
        AuthorId author = authors.save(new Author("Extension Writer")).getId();
        a = save(MARKER + " A", "isbn-order-a", author);
        b = save(MARKER + " B", "isbn-order-b", author);
        c = save(MARKER + " C", "isbn-order-c", author);
    }

    private BookId save(String title, String isbn, AuthorId author) {
        return books.save(new Book(title, List.of(author), Set.of(), isbn, 2000, null)).getId();
    }

    private BookSearch markedBooks() {
        return new BookSearch(MARKER, null, null, null);
    }

    @Test
    void ordersByTheOtherModulesOrderThenTitle() {
        BookSearch search = markedBooks().ordered((id, query, cb) -> cb.desc(id));

        Page<BookDetails> first = catalog.findBooks(search, PageRequest.of(0, 2, Sort.by("title")));
        Page<BookDetails> second = catalog.findBooks(search, PageRequest.of(1, 2, Sort.by("title")));

        assertThat(first.getContent()).extracting(BookDetails::id).containsExactly(c, b);
        assertThat(second.getContent()).extracting(BookDetails::id).containsExactly(a);
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
    }

    @Test
    void matchingTwiceAndsTheConditions() {
        BookCondition notA = (id, query, cb) -> cb.notEqual(id, a);
        BookCondition notC = (id, query, cb) -> cb.notEqual(id, c);

        Page<BookDetails> found = catalog.findBooks(markedBooks().matching(notA).matching(notC),
                PageRequest.of(0, 10, Sort.by("title")));

        assertThat(found.getContent()).extracting(BookDetails::id).containsExactly(b);
    }

    @Test
    void matchingOnceKeepsTheConditionItself() {
        BookCondition condition = (id, query, cb) -> cb.conjunction();

        assertThat(markedBooks().matching(condition).condition()).isSameAs(condition);
    }
}
