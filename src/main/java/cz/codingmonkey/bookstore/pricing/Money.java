package cz.codingmonkey.bookstore.pricing;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in a currency, e.g. 12.90 EUR. The amount always has the currency's number of decimal places
 * (2 for EUR), so equal amounts are equal regardless of how they were written ("12.9" and "12.90").
 */
@Embeddable
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        int decimals = currency.getDefaultFractionDigits();
        if (amount.stripTrailingZeros().scale() > decimals) {
            throw new IllegalArgumentException(
                    "%s has at most %d decimal places, got %s".formatted(currency, decimals, amount));
        }
        amount = amount.setScale(decimals, RoundingMode.UNNECESSARY);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
