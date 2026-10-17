package cz.codingmonkey.bookstore.inventory;

import cz.codingmonkey.bookstore.shared.LongIdType;

/** Persists {@link ReservationId} as {@code BIGINT}. */
public class ReservationIdType extends LongIdType<ReservationId> {

    public ReservationIdType() {
        super(ReservationId.class, ReservationId::new);
    }
}
