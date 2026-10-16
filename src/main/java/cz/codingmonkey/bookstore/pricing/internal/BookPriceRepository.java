package cz.codingmonkey.bookstore.pricing.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface BookPriceRepository extends JpaRepository<BookPrice, BookId> {

    Page<BookPrice> findAllBy(Pageable page);

    Page<BookPrice> findByBookIdIn(Collection<BookId> bookIds, Pageable page);
}
