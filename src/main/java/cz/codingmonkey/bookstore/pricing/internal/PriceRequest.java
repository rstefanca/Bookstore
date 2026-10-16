package cz.codingmonkey.bookstore.pricing.internal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * @param amount   e.g. {@code 12.90}; at most 2 decimal places
 * @param currency ISO 4217 code; optional, defaults to the store currency, which is also the only one accepted
 */
public record PriceRequest(@NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount, String currency) {
}
