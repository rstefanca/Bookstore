package cz.codingmonkey.bookstore.security;

/**
 * What an account may do. {@code ADMIN} includes everything {@code STAFF} may do; {@code CUSTOMER} has none of it.
 */
enum Role {

    /** Maintains the catalog, inventory and prices. */
    STAFF,

    /** Also reads the audit log and manages accounts. */
    ADMIN,

    /** Signed up themselves; browses like anyone and manages only their own account. */
    CUSTOMER
}
