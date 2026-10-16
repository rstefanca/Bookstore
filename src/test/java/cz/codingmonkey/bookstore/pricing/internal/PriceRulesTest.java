package cz.codingmonkey.bookstore.pricing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cz.codingmonkey.bookstore.pricing.Money;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Plain unit test: no Spring, no database.
 */
class PriceRulesTest {

    final PriceRules euros = new PriceRules(Currency.getInstance("EUR"));

    @Test
    void acceptsAmountsInTheStoreCurrency() {
        assertThat(euros.toPrice(new BigDecimal("12.9"), "EUR")).isEqualTo(Money.of("12.90", "EUR"));
        assertThat(euros.toPrice(new BigDecimal("0.01"), "EUR")).isEqualTo(Money.of("0.01", "EUR"));
    }

    @Test
    void defaultsToTheStoreCurrency() {
        assertThat(euros.toPrice(new BigDecimal("10"), null)).isEqualTo(Money.of("10.00", "EUR"));
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            0      | EUR  | Prices must be positive, got 0
            -5     | EUR  | Prices must be positive, got -5
            0.00   |      | Prices must be positive, got 0.00
            10     | USD  | Prices must be in EUR, got USD
            10     | eur  | Unknown currency code: eur
            10     | XYZ  | Unknown currency code: XYZ
            10     | ''   | 'Unknown currency code: '
            9.999  | EUR  | EUR has at most 2 decimal places, got 9.999
            """)
    void rejectsInvalidPrices(String amount, String currency, String message) {
        assertThatThrownBy(() -> euros.toPrice(new BigDecimal(amount), currency))
                .isInstanceOf(InvalidPriceException.class)
                .hasMessage(message);
    }

    @Test
    void rejectsAMissingAmount() {
        assertThatThrownBy(() -> euros.toPrice(null, "EUR"))
                .isInstanceOf(InvalidPriceException.class).hasMessage("A price needs an amount");
    }

    @Test
    void checksTheAmountBeforeTheCurrency() {
        assertThatThrownBy(() -> euros.toPrice(new BigDecimal("-1"), "USD"))
                .hasMessageStartingWith("Prices must be positive");
    }

    @Test
    void followsTheStoreCurrency() {
        var yen = new PriceRules(Currency.getInstance("JPY"));

        assertThat(yen.toPrice(new BigDecimal("1500"), null)).isEqualTo(Money.of("1500", "JPY"));
        assertThatThrownBy(() -> yen.toPrice(new BigDecimal("1500.5"), null))
                .as("yen has no minor unit").isInstanceOf(InvalidPriceException.class);
        assertThatThrownBy(() -> yen.toPrice(new BigDecimal("10"), "EUR"))
                .hasMessage("Prices must be in JPY, got EUR");
    }
}
