package cz.codingmonkey.bookstore.pricing.internal;

import cz.codingmonkey.bookstore.shared.LongId;

record PricePeriodId(long value) implements LongId {

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
