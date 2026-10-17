/**
 * Audit module. Records an audit trail by listening to the events other modules mark as {@code shared.Auditable}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Audit", allowedDependencies = "shared")
package cz.codingmonkey.bookstore.audit;
