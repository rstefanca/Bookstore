package cz.codingmonkey.bookstore.pricing.internal;

import cz.codingmonkey.bookstore.pricing.Money;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * @param from  when the price took effect
 * @param until when it stopped applying; {@code null} for the current price
 */
public record PricePeriodResponse(Money price, Instant from, @Nullable Instant until) {

    static PricePeriodResponse from(PricePeriod period) {
        return new PricePeriodResponse(period.getPrice(), period.getValidFrom(), period.getValidUntil());
    }
}
