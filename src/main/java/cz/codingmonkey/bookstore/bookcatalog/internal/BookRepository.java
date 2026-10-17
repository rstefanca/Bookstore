package cz.codingmonkey.bookstore.bookcatalog.internal;

import cz.codingmonkey.bookstore.bookcatalog.AuthorId;
import cz.codingmonkey.bookstore.bookcatalog.BookId;
import cz.codingmonkey.bookstore.bookcatalog.GenreId;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface BookRepository extends JpaRepository<Book, BookId>, JpaSpecificationExecutor<Book> {

    boolean existsByAuthorIdsContaining(AuthorId authorId);

    boolean existsByGenreIdsContaining(GenreId genreId);

    boolean existsByIsbn(String isbn);

    /**
     * {@code SELECT ... FOR KEY SHARE}: blocks deletion of the book until the transaction ends, but not ordinary
     * updates of it (JPA has no lock mode for this, hence the native query).
     *
     * @return the id if the book exists
     */
    @Query(value = "SELECT id FROM book WHERE id = :id FOR KEY SHARE", nativeQuery = true)
    Optional<Long> findIdAndKeyShareLock(long id);

    /**
     * {@code SELECT ... FOR UPDATE}: waits for, and then blocks, transactions holding a key share lock. Native,
     * because Hibernate's {@code PESSIMISTIC_WRITE} is {@code FOR NO KEY UPDATE} on PostgreSQL, which doesn't conflict
     * with {@code FOR KEY SHARE}.
     */
    @Query(value = "SELECT * FROM book WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Book> findAndLockById(long id);
}
