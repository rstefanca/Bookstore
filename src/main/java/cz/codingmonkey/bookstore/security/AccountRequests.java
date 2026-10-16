package cz.codingmonkey.bookstore.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies of the account API. Password rules are checked by {@link AccountService}. */
final class AccountRequests {

    private AccountRequests() {
    }

    record NewAccount(
            @NotBlank String username,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotNull String password,
            @NotNull Role role) {
    }

    /** Self sign-up: no role, it is always {@link Role#CUSTOMER}. */
    record Registration(
            @NotBlank String username,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotNull String password) {
    }

    record Names(@NotBlank @Size(max = 100) String firstName, @NotBlank @Size(max = 100) String lastName) {
    }

    record NewPassword(@NotNull String password) {
    }

    record PasswordChange(@NotNull String currentPassword, @NotNull String newPassword) {
    }
}
