package cz.codingmonkey.bookstore.shared;

/** A signed-in principal that knows which account it belongs to. */
public interface AccountPrincipal {

    AccountId accountId();
}
