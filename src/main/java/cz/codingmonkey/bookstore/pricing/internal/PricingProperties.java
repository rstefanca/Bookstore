package cz.codingmonkey.bookstore.pricing.internal;

import java.util.Currency;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param currency the store currency; every price must be in it
 */
@ConfigurationProperties("bookstore.pricing")
record PricingProperties(Currency currency) {
}
