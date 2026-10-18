package cz.codingmonkey.bookstore.rating.internal;

import cz.codingmonkey.bookstore.security.AccountDeletionPolicy;
import cz.codingmonkey.bookstore.shared.AccountId;
import org.springframework.stereotype.Component;

/**
 * Ratings outlive their customer's account and keep counting; they carry no personal data besides the account id,
 * which is never reused.
 */
@Component
class RatingAccountDeletion implements AccountDeletionPolicy {

    private final BookRatings ratings;

    RatingAccountDeletion(BookRatings ratings) {
        this.ratings = ratings;
    }

    @Override
    public boolean holdsRecords(AccountId account) {
        return ratings.existsBy(account);
    }

    @Override
    public void removePersonalData(AccountId account) {
    }

    @Override
    public void anonymize(AccountId account) {
    }
}
