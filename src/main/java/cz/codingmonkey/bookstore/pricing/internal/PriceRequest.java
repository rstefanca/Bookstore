package cz.codingmonkey.bookstore.pricing.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * A book's new price.
 *
 * @param amount   e.g. {@code 12.90}; at most 2 decimal places
 * @param currency ISO 4217 code; optional, defaults to the store currency, which is also the only one accepted
 */
public record PriceRequest(
        @Schema(example = "12.90") @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @Schema(example = "EUR") @Nullable String currency) {
}
