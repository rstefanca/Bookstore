package cz.codingmonkey.bookstore.payment.internal;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param declineAbove the fake provider declines charges above this amount, so the failure path is easy to try
 */
@ConfigurationProperties("bookstore.payment")
record PaymentProperties(@DefaultValue("100") BigDecimal declineAbove) {
}
