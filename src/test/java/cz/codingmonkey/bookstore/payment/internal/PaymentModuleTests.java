package cz.codingmonkey.bookstore.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cz.codingmonkey.bookstore.TestcontainersConfiguration;
import cz.codingmonkey.bookstore.payment.PaymentFailed;
import cz.codingmonkey.bookstore.payment.PaymentReference;
import cz.codingmonkey.bookstore.payment.PaymentRefunded;
import cz.codingmonkey.bookstore.payment.PaymentRequested;
import cz.codingmonkey.bookstore.payment.PaymentSucceeded;
import cz.codingmonkey.bookstore.payment.RefundRequested;
import cz.codingmonkey.bookstore.pricing.Money;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.modulith.test.Scenario;

/**
 * Only the payment module runs; the test asks for charges and refunds the way the ordering module does.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class PaymentModuleTests {

    static final AtomicLong REFERENCES = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    PaymentRepository payments;

    @Autowired
    ApplicationEventPublisher publisher;

    @Test
    void chargesAmountsUpToTheLimit(Scenario scenario) {
        PaymentReference reference = newReference();

        scenario.publish(new PaymentRequested(reference, Money.of("100.00", "EUR"), "rita"))
                .andWaitForEventOfType(PaymentSucceeded.class)
                .matching(succeeded -> succeeded.reference().equals(reference))
                .toArrive();

        assertThat(status(reference)).isEqualTo(Payment.Status.CHARGED);
    }

    @Test
    void declinesAmountsAboveTheLimit(Scenario scenario) {
        PaymentReference reference = newReference();

        scenario.publish(new PaymentRequested(reference, Money.of("100.01", "EUR"), "rita"))
                .andWaitForEventOfType(PaymentFailed.class)
                .matching(failed -> failed.reference().equals(reference))
                .toArriveAndVerify(failed -> assertThat(failed.reason()).contains("above 100"));

        assertThat(status(reference)).isEqualTo(Payment.Status.DECLINED);
    }

    @Test
    void repeatedRequestsGetTheSameAnswer(PublishedEvents events, Scenario scenario) {
        PaymentReference reference = newReference();
        var request = new PaymentRequested(reference, Money.of("10", "EUR"), "rita");

        scenario.stimulate(() -> {
                    publisher.publishEvent(request);
                    publisher.publishEvent(request);
                })
                .andWaitForStateChange(() -> count(events.ofType(PaymentSucceeded.class)
                        .matching(s -> s.reference().equals(reference))) >= 1 ? true : null)
                .andVerify(done -> { });

        assertThat(payments.findAll()).filteredOn(p -> p.getReference().equals(reference)).hasSize(1);
    }

    @Test
    void refundsOnce(PublishedEvents events, Scenario scenario) {
        PaymentReference reference = newReference();
        charge(scenario, reference);

        scenario.stimulate(() -> {
                    publisher.publishEvent(new RefundRequested(reference, "rita"));
                    publisher.publishEvent(new RefundRequested(reference, "rita"));
                })
                .andWaitForEventOfType(PaymentRefunded.class)
                .matching(refunded -> refunded.reference().equals(reference))
                .toArrive();

        assertThat(status(reference)).isEqualTo(Payment.Status.REFUNDED);
    }

    @Test
    void refundIsAnnouncedOnce(PublishedEvents events, Scenario scenario) {
        PaymentReference reference = newReference();
        charge(scenario, reference);
        scenario.publish(new RefundRequested(reference, "rita"))
                .andWaitForEventOfType(PaymentRefunded.class)
                .matching(refunded -> refunded.reference().equals(reference))
                .toArrive();

        scenario.stimulate(() -> publisher.publishEvent(new RefundRequested(reference, "rita")))
                .andWaitForStateChange(() -> true).andVerify(x -> { });

        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(events.ofType(PaymentRefunded.class).matching(r -> r.reference().equals(reference)))
                        .hasSize(1));
    }

    @Test
    void doesNotRefundDeclinedPayments(Scenario scenario) {
        PaymentReference reference = newReference();
        scenario.publish(new PaymentRequested(reference, Money.of("500", "EUR"), "rita"))
                .andWaitForEventOfType(PaymentFailed.class)
                .matching(failed -> failed.reference().equals(reference))
                .toArrive();

        scenario.stimulate(() -> publisher.publishEvent(new RefundRequested(reference, "rita")))
                .andWaitForStateChange(() -> true).andVerify(x -> { });

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(status(reference)).isEqualTo(Payment.Status.DECLINED));
    }

    private void charge(Scenario scenario, PaymentReference reference) {
        scenario.publish(new PaymentRequested(reference, Money.of("10", "EUR"), "rita"))
                .andWaitForEventOfType(PaymentSucceeded.class)
                .matching(succeeded -> succeeded.reference().equals(reference))
                .toArrive();
    }

    private Payment.Status status(PaymentReference reference) {
        return payments.findById(reference).orElseThrow().getStatus();
    }

    private static long count(Iterable<?> events) {
        return StreamSupport.stream(events.spliterator(), false).count();
    }

    private static PaymentReference newReference() {
        return new PaymentReference(REFERENCES.incrementAndGet());
    }
}
