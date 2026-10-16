package cz.codingmonkey.bookstore.ordering.internal;

import org.springframework.data.jpa.repository.JpaRepository;

interface CartRepository extends JpaRepository<Cart, String> {
}
