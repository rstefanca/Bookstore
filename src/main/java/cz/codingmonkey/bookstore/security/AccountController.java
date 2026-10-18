package cz.codingmonkey.bookstore.security;

import cz.codingmonkey.bookstore.security.AccountRequests.Names;
import cz.codingmonkey.bookstore.security.AccountRequests.NewAccount;
import cz.codingmonkey.bookstore.security.AccountRequests.NewPassword;
import cz.codingmonkey.bookstore.security.AccountRequests.PasswordChange;
import cz.codingmonkey.bookstore.security.AccountRequests.Registration;
import cz.codingmonkey.bookstore.shared.AccountId;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account management (admins), customer sign-up ({@code /register}, anyone) and the signed-in account's own
 * details ({@code /me}, everyone signed in).
 */
@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Accounts", description = """
        Sign-up (anyone), your own account (`/me`, any signed-in account) and managing all accounts (`ADMIN`). \
        Usernames are 3 to 100 letters, digits, dots, underscores or hyphens, unique regardless of case; passwords \
        need 12 to 72 bytes.""")
class AccountController {

    private final AccountService service;

    AccountController(AccountService service) {
        this.service = service;
    }

    /** Lists accounts, sorted by username. */
    @GetMapping
    PagedModel<AccountResponse> list(Pageable page) {
        var byUsername = PageRequest.of(page.getPageNumber(), page.getPageSize(), Sort.by("username"));
        return new PagedModel<>(service.findAll(byUsername));
    }

    /**
     * Creates an account with any role, such as a new staff member's.
     * <p>
     * {@code Location} is the new account's URL.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "The new account")
    @ApiResponse(responseCode = "400", description = "Invalid fields, username or password")
    @ApiResponse(responseCode = "409", description = "The username is taken (in any case)")
    ResponseEntity<AccountResponse> create(@Valid @RequestBody NewAccount request) {
        AccountResponse created = service.create(request.username(), request.firstName(), request.lastName(),
                request.password(), request.role());
        return ResponseEntity.created(URI.create("/api/accounts/" + created.id())).body(created);
    }

    /**
     * Signs up as a customer.
     * <p>
     * Open to anyone. {@code Location} is the new account's URL; sign in with the username and password from then
     * on.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @ApiResponse(responseCode = "201", description = "The new customer account")
    @ApiResponse(responseCode = "400", description = "Invalid fields, username or password")
    @ApiResponse(responseCode = "409", description = "The username is taken (in any case)")
    ResponseEntity<AccountResponse> register(@Valid @RequestBody Registration request) {
        AccountResponse created = service.register(request.username(), request.firstName(), request.lastName(),
                request.password());
        return ResponseEntity.created(URI.create("/api/accounts/" + created.id())).body(created);
    }

    /** Changes an account's name. */
    @PutMapping("/{id}")
    @ApiResponse(responseCode = "200", description = "The updated account")
    @ApiResponse(responseCode = "404", description = "No account with this id")
    AccountResponse rename(@PathVariable AccountId id, @Valid @RequestBody Names request) {
        return service.rename(id, request.firstName(), request.lastName());
    }

    /** Sets a new password for an account, e.g. when its owner forgot theirs. */
    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Password changed")
    @ApiResponse(responseCode = "400", description = "The password is too short or too long")
    @ApiResponse(responseCode = "404", description = "No account with this id")
    void resetPassword(@PathVariable AccountId id, @Valid @RequestBody NewPassword request) {
        service.resetPassword(id, request.password());
    }

    /** Deletes an account. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No account with this id")
    @ApiResponse(responseCode = "409", description = "It is your own account, or the last admin account")
    void delete(@PathVariable AccountId id, Principal principal) {
        service.delete(id, principal.getName());
    }

    /** Returns the signed-in account, e.g. to check credentials or see your role. */
    @GetMapping("/me")
    AccountResponse me(Principal principal) {
        return service.findByUsername(principal.getName());
    }

    /** Changes your name. */
    @PutMapping("/me")
    AccountResponse renameOwn(@Valid @RequestBody Names request, Principal principal) {
        return service.renameOwn(principal.getName(), request.firstName(), request.lastName());
    }

    /** Changes your password; sign in with the new one from then on. */
    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Password changed")
    @ApiResponse(responseCode = "400",
            description = "The current password is wrong, or the new one is too short or too long")
    void changeOwnPassword(@Valid @RequestBody PasswordChange request, Principal principal) {
        service.changeOwnPassword(principal.getName(), request.currentPassword(), request.newPassword());
    }

    /** Two requests created the same username at the same time. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail duplicateUsername() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The username was taken in the meantime");
    }
}
