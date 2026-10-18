/**
 * Audit module. Records an audit trail by listening to the events other modules mark as {@code shared.Auditable}.
 * Actors are stored by account id; their usernames are looked up in the security module when entries are read.
 */
@org.jspecify.annotations.NullMarked
@org.springframework.modulith.ApplicationModule(displayName = "Audit", allowedDependencies = {"shared", "security"})
package cz.codingmonkey.bookstore.audit;
