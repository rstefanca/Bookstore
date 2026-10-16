/**
 * Payment module: a fake payment provider for the demo. Charges on
 * {@link cz.codingmonkey.bookstore.payment.PaymentRequested} (declining amounts above
 * {@code bookstore.payment.decline-above}) and refunds on {@link cz.codingmonkey.bookstore.payment.RefundRequested}.
 * It doesn't know what is being paid for.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Payment")
package cz.codingmonkey.bookstore.payment;
