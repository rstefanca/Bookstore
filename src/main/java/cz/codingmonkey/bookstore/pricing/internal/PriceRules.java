package cz.codingmonkey.bookstore.pricing.internal;

import cz.codingmonkey.bookstore.pricing.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Turns a requested amount and currency into a valid price, or explains why it isn't one: the amount must be
 * positive, have no more decimal places than the currency allows, and be in the store currency.
 *
 * @param storeCurrency the only currency prices may be in
 */
record PriceRules(Currency storeCurrency) {

    PriceRules {
        Objects.requireNonNull(storeCurrency, "storeCurrency");
    }

    /**
     * @param currencyCode ISO 4217 code; {@code null} means the store currency
     * @throws InvalidPriceException if the amount or currency isn't acceptable
     */
    Money toPrice(BigDecimal amount, String currencyCode) {
        if (amount == null) {
            throw new InvalidPriceException("A price needs an amount");
        }
        if (amount.signum() <= 0) {
            throw new InvalidPriceException("Prices must be positive, got " + amount.toPlainString());
        }
        Currency currency = currencyOf(currencyCode);
        if (!currency.equals(storeCurrency)) {
            throw new InvalidPriceException("Prices must be in %s, got %s".formatted(storeCurrency, currency));
        }
        try {
            return new Money(amount, currency);
        } catch (IllegalArgumentException e) {
            throw new InvalidPriceException(e.getMessage()); // too many decimal places
        }
    }

    private Currency currencyOf(String currencyCode) {
        if (currencyCode == null) {
            return storeCurrency;
        }
        try {
            return Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new InvalidPriceException("Unknown currency code: " + currencyCode);
        }
    }
}
