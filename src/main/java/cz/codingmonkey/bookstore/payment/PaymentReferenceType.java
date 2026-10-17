package cz.codingmonkey.bookstore.payment;

import cz.codingmonkey.bookstore.shared.LongIdType;

/** Persists {@link PaymentReference} as {@code BIGINT}. */
public class PaymentReferenceType extends LongIdType<PaymentReference> {

    public PaymentReferenceType() {
        super(PaymentReference.class, PaymentReference::new);
    }
}
