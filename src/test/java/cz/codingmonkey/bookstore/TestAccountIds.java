package cz.codingmonkey.bookstore;

import cz.codingmonkey.bookstore.shared.AccountId;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/** Unique account ids for tests that have no real accounts. */
public final class TestAccountIds {

    private static final AtomicLong NEXT = new AtomicLong(ThreadLocalRandom.current().nextLong(1_000_000_000L,
            1_000_000_000_000L));

    private TestAccountIds() {
    }

    public static AccountId next() {
        return new AccountId(NEXT.getAndIncrement());
    }
}
