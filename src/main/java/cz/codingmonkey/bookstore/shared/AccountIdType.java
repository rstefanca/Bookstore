package cz.codingmonkey.bookstore.shared;

import cz.codingmonkey.bookstore.shared.LongIdType;

public class AccountIdType extends LongIdType<AccountId> {

    AccountIdType() {
        super(AccountId.class, AccountId::new);
    }
}
