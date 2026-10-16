package cz.codingmonkey.bookstore.security;

import cz.codingmonkey.bookstore.shared.LongIdType;

class AccountIdType extends LongIdType<AccountId> {

    AccountIdType() {
        super(AccountId.class, AccountId::new, AccountId::value);
    }
}
