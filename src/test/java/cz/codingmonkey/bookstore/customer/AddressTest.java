package cz.codingmonkey.bookstore.customer;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AddressTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> invalidFields(Address a) {
        return validator.validate(a).stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void stripsAndUpperCasesCountry() {
        Address a = new Address(" Bea ", " Main 1 ", " Brno ", " 602 00 ", "cz");
        assertThat(a).isEqualTo(new Address("Bea", "Main 1", "Brno", "602 00", "CZ"));
    }

    @Test
    void rejectsBlankAndMissingFields() {
        for (String bad : new String[] {"  ", null}) {
            assertThat(invalidFields(new Address(bad, "s", "c", "p", "CZ"))).containsExactly("name");
            assertThat(invalidFields(new Address("n", bad, "c", "p", "CZ"))).containsExactly("street");
            assertThat(invalidFields(new Address("n", "s", bad, "p", "CZ"))).containsExactly("city");
            assertThat(invalidFields(new Address("n", "s", "c", bad, "CZ"))).containsExactly("postalCode");
            Set<ConstraintViolation<Address>> v = validator.validate(new Address("n", "s", "c", "p", bad));
            assertThat(v).hasSize(1);
            assertThat(v.iterator().next().getPropertyPath().toString()).isEqualTo("country");
        }
    }

    @Test
    void rejectsTooLongAndBadCountry() {
        assertThat(invalidFields(new Address("x".repeat(101), "s", "c", "p", "CZ"))).containsExactly("name");
        assertThat(invalidFields(new Address("n", "x".repeat(201), "c", "p", "CZ"))).containsExactly("street");
        assertThat(invalidFields(new Address("n", "s", "x".repeat(101), "p", "CZ"))).containsExactly("city");
        assertThat(invalidFields(new Address("n", "s", "c", "x".repeat(21), "CZ"))).containsExactly("postalCode");
        for (String country : new String[] {"C", "CZE", "C1"}) {
            assertThat(invalidFields(new Address("n", "s", "c", "p", country))).containsExactly("country");
        }
    }
}
