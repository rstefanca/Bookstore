package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

public interface BookRepository extends JpaRepository<Book, BookId>, JpaSpecificationExecutor<Book> {

    boolean existsByAuthorIdsContaining(AuthorId authorId);

    boolean existsByGenreIdsContaining(GenreId genreId);

    boolean existsByIsbn(String isbn);

    /** {@code SELECT ... FOR SHARE}: blocks deletion of the book until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<Book> findAndShareLockById(BookId id);

    /** {@code SELECT ... FOR UPDATE}: waits for, and then blocks, transactions holding a share lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Book> findAndLockById(BookId id);
}
