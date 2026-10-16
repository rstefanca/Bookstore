package cz.codingmonkey.bookstore.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void normalizesToTheCurrencysDecimalPlaces() {
        assertThat(Money.of("12.9", "EUR").amount()).isEqualByComparingTo("12.90").hasScaleOf(2);
        assertThat(Money.of("12.9", "EUR")).isEqualTo(Money.of("12.900", "EUR"));
        assertThat(Money.of("12", "EUR")).hasToString("12.00 EUR");
    }

    @Test
    void rejectsMoreDecimalPlacesThanTheCurrencyHas() {
        assertThatThrownBy(() -> Money.of("12.999", "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 2 decimal places");
        assertThatThrownBy(() -> Money.of("100.5", "JPY")).as("yen has no minor unit")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void differentCurrenciesAreNotEqual() {
        assertThat(Money.of("10", "EUR")).isNotEqualTo(Money.of("10", "USD"));
        assertThat(new Money(BigDecimal.TEN, Currency.getInstance("EUR"))).isEqualTo(Money.of("10.00", "EUR"));
    }
}
