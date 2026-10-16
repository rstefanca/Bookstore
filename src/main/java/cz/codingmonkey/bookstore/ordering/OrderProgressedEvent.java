package cz.codingmonkey.bookstore.ordering;

import java.time.Instant;
import org.jmolecules.event.annotation.DomainEvent;

/**
 * Published by the ordering module for every step an order goes through, including the stock and payment steps
 * handled by other modules, so the whole purchase can be followed (e.g. in the audit log). Published only when the
 * order really moves on, so repeated or late results don't produce extra steps.
 *
 * @param detail short human-readable text, e.g. "Charged 38.97 EUR"
 * @param actor  the order's customer (username), also for steps handled in the background
 * @param occurredAt when the step happened; later than the order's previous steps, even ones published together
 */
@DomainEvent
public record OrderProgressedEvent(OrderId orderId, OrderStep step, String detail, String actor,
        Instant occurredAt) {
}
