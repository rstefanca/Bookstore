package cz.codingmonkey.bookstore.customer;

/**
 * Test fixture: two different valid addresses.
 */
public final class TestAddresses {

    public static final Address SHIPPING = new Address("Bea Novak", "Main 1", "Brno", "602 00", "CZ");
    public static final Address INVOICE = new Address("Novak s.r.o.", "Office 9", "Praha", "110 00", "CZ");

    private TestAddresses() {
    }

    public static String json(Address a) {
        return """
                {"name":"%s","street":"%s","city":"%s","postalCode":"%s","country":"%s"}"""
                .formatted(a.name(), a.street(), a.city(), a.postalCode(), a.country());
    }
}
