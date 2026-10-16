package cz.codingmonkey.bookstore.payment.internal;

import cz.codingmonkey.bookstore.payment.PaymentFailed;
import cz.codingmonkey.bookstore.payment.PaymentRefunded;
import cz.codingmonkey.bookstore.payment.PaymentRequested;
import cz.codingmonkey.bookstore.payment.PaymentSucceeded;
import cz.codingmonkey.bookstore.payment.RefundRequested;
import cz.codingmonkey.bookstore.shared.ChangeLog;
import java.math.BigDecimal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * A fake payment provider. Charges are stored by reference, so a repeated request (retries) gets the earlier
 * answer instead of charging twice.
 */
@Service
@EnableConfigurationProperties(PaymentProperties.class)
class PaymentService {

    private static final Logger log = LogManager.getLogger(PaymentService.class);
    private static final ChangeLog changes = ChangeLog.of(PaymentService.class);

    private final PaymentRepository payments;
    private final ApplicationEventPublisher events;
    private final BigDecimal declineAbove;

    PaymentService(PaymentRepository payments, ApplicationEventPublisher events, PaymentProperties properties) {
        this.payments = payments;
        this.events = events;
        this.declineAbove = properties.declineAbove();
    }

    @ApplicationModuleListener
    void on(PaymentRequested request) {
        Payment payment = payments.findById(request.reference()).orElse(null);

        if (payment == null) {
            if (request.amount().amount().compareTo(declineAbove) > 0) {
                payment = payments.save(Payment.declined(request.reference(), request.amount(),
                        "Declined: amounts above %s are refused".formatted(declineAbove.toPlainString())));
                changes.info("Declined payment {} of {}", payment.getReference(), payment.getAmount());
            } else {
                payment = payments.save(Payment.charged(request.reference(), request.amount()));
                changes.info("Charged payment {} of {}", payment.getReference(), payment.getAmount());
            }
        } else {
            log.debug("Payment {} was already handled; repeating its answer", request.reference());
        }

        switch (payment.getStatus()) {
            case CHARGED -> events.publishEvent(new PaymentSucceeded(payment.getReference()));
            case DECLINED -> events.publishEvent(new PaymentFailed(payment.getReference(), payment.getReason()));
            case REFUNDED -> log.debug("Payment {} was already refunded; not answering again", request.reference());
        }
    }

    /**
     * Refunds once and announces it once; the payment row is locked, so a repeated request running at the same
     * time waits and then finds it refunded.
     */
    @ApplicationModuleListener
    void on(RefundRequested request) {
        Payment payment = payments.findAndLockByReference(request.reference()).orElse(null);

        if (payment == null) {
            log.warn("Refund requested for unknown payment {}", request.reference());
            return;
        }
        if (payment.refund()) {
            events.publishEvent(new PaymentRefunded(payment.getReference()));
            changes.info("Refunded payment {} of {}", payment.getReference(), payment.getAmount());
        } else {
            log.debug("Payment {} is {}; nothing to refund", payment.getReference(), payment.getStatus());
        }
    }
}
