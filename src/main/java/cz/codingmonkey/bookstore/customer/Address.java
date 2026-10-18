package cz.codingmonkey.bookstore.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A postal address. All fields are required; the constructor strips them and upper-cases the country. Missing
 * fields are kept as {@code null} so that bean validation reports them instead of the constructor throwing.
 *
 * @param name       the recipient: a person or a company
 * @param street     street and number
 * @param city       city
 * @param postalCode postal code
 * @param country    ISO 3166-1 alpha-2, upper case
 */
@Embeddable
public record Address(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 200) String street,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 20) String postalCode,
        // The empty alternative leaves a blank country to @NotBlank alone, so each field reports one violation.
        @NotBlank @Pattern(regexp = "[A-Z]{2}|", message = "must be a two-letter ISO 3166-1 alpha-2 code")
        @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 2) String country) {

    @SuppressWarnings("NullAway")
    public Address {
        name = strip(name);
        street = strip(street);
        city = strip(city);
        postalCode = strip(postalCode);
        country = country == null ? null : country.strip().toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("NullAway")
    private static @Nullable String strip(@Nullable String s) {
        return s == null ? null : s.strip();
    }
}
