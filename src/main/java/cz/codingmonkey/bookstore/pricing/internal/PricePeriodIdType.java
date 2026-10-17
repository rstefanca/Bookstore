package cz.codingmonkey.bookstore.pricing.internal;

import cz.codingmonkey.bookstore.shared.LongIdType;

class PricePeriodIdType extends LongIdType<PricePeriodId> {

    PricePeriodIdType() {
        super(PricePeriodId.class, PricePeriodId::new);
    }
}
