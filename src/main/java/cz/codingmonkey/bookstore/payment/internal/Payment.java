package cz.codingmonkey.bookstore.payment.internal;

import cz.codingmonkey.bookstore.payment.PaymentReference;
import cz.codingmonkey.bookstore.payment.PaymentReferenceType;
import cz.codingmonkey.bookstore.pricing.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.Type;

@Entity
class Payment {

    enum Status { CHARGED, DECLINED, REFUNDED }

    @Id
    @Type(PaymentReferenceType.class)
    private PaymentReference reference;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "amount", nullable = false, precision = 12, scale = 2))
    @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false, length = 3))
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    protected Payment() {
    }

    private Payment(PaymentReference reference, Money amount, Status status, String reason) {
        this.reference = reference;
        this.amount = amount;
        this.status = status;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    static Payment charged(PaymentReference reference, Money amount) {
        return new Payment(reference, amount, Status.CHARGED, null);
    }

    static Payment declined(PaymentReference reference, Money amount, String reason) {
        return new Payment(reference, amount, Status.DECLINED, reason);
    }

    PaymentReference getReference() { return reference; }
    Money getAmount() { return amount; }
    Status getStatus() { return status; }
    String getReason() { return reason; }

    /** @return whether this refunds the payment now (only a charged payment, only once) */
    boolean refund() {
        if (status != Status.CHARGED) {
            return false;
        }
        status = Status.REFUNDED;
        return true;
    }
}
