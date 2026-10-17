package cz.codingmonkey.bookstore.security;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies of the account API. Password rules are checked by {@link AccountService}. */
final class AccountRequests {

    private AccountRequests() {
    }

    /** An account created by an admin. */
    record NewAccount(
            @Schema(example = "sam.staff") @NotBlank String username,
            @Schema(example = "Sam") @NotBlank @Size(max = 100) String firstName,
            @Schema(example = "Staff") @NotBlank @Size(max = 100) String lastName,
            @Schema(example = "correct-horse-battery") @NotNull String password,
            @Schema(example = "STAFF") @NotNull Role role) {
    }

    /** Self sign-up: no role, it is always {@link Role#CUSTOMER}. */
    record Registration(
            @Schema(example = "anna.novak") @NotBlank String username,
            @Schema(example = "Anna") @NotBlank @Size(max = 100) String firstName,
            @Schema(example = "Novák") @NotBlank @Size(max = 100) String lastName,
            @Schema(example = "correct-horse-battery") @NotNull String password) {
    }

    /** A person's name. */
    record Names(
            @Schema(example = "Anna") @NotBlank @Size(max = 100) String firstName,
            @Schema(example = "Novák") @NotBlank @Size(max = 100) String lastName) {
    }

    /** A password set by an admin. */
    record NewPassword(@Schema(example = "correct-horse-battery") @NotNull String password) {
    }

    /** Your new password, confirmed with the current one. */
    record PasswordChange(
            @NotNull String currentPassword,
            @Schema(example = "correct-horse-battery") @NotNull String newPassword) {
    }
}
