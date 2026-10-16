package cz.codingmonkey.bookstore.pricing.internal;

import cz.codingmonkey.bookstore.bookcatalog.BookId;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface PricePeriodRepository extends JpaRepository<PricePeriod, PricePeriodId> {

    Optional<PricePeriod> findByBookIdAndValidUntilIsNull(BookId bookId);

    Page<PricePeriod> findByBookId(BookId bookId, Pageable page);
}
