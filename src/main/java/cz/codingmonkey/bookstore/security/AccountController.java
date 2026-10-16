package cz.codingmonkey.bookstore.security;

import cz.codingmonkey.bookstore.security.AccountRequests.Names;
import cz.codingmonkey.bookstore.security.AccountRequests.NewAccount;
import cz.codingmonkey.bookstore.security.AccountRequests.NewPassword;
import cz.codingmonkey.bookstore.security.AccountRequests.PasswordChange;
import cz.codingmonkey.bookstore.security.AccountRequests.Registration;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
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
class AccountController {

    private final AccountService service;

    AccountController(AccountService service) {
        this.service = service;
    }

    @GetMapping
    PagedModel<AccountResponse> list(Pageable page) {
        var byUsername = PageRequest.of(page.getPageNumber(), page.getPageSize(), Sort.by("username"));
        return new PagedModel<>(service.findAll(byUsername));
    }

    @PostMapping
    ResponseEntity<AccountResponse> create(@Valid @RequestBody NewAccount request) {
        AccountResponse created = service.create(request.username(), request.firstName(), request.lastName(),
                request.password(), request.role());
        return ResponseEntity.created(URI.create("/api/accounts/" + created.id())).body(created);
    }

    /** Open to anyone: creates a customer account. */
    @PostMapping("/register")
    ResponseEntity<AccountResponse> register(@Valid @RequestBody Registration request) {
        AccountResponse created = service.register(request.username(), request.firstName(), request.lastName(),
                request.password());
        return ResponseEntity.created(URI.create("/api/accounts/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    AccountResponse rename(@PathVariable AccountId id, @Valid @RequestBody Names request) {
        return service.rename(id, request.firstName(), request.lastName());
    }

    @PutMapping("/{id}/password")
    ResponseEntity<Void> resetPassword(@PathVariable AccountId id, @Valid @RequestBody NewPassword request) {
        service.resetPassword(id, request.password());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable AccountId id, Principal principal) {
        service.delete(id, principal.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    AccountResponse me(Principal principal) {
        return service.findByUsername(principal.getName());
    }

    @PutMapping("/me")
    AccountResponse renameOwn(@Valid @RequestBody Names request, Principal principal) {
        return service.renameOwn(principal.getName(), request.firstName(), request.lastName());
    }

    @PutMapping("/me/password")
    ResponseEntity<Void> changeOwnPassword(@Valid @RequestBody PasswordChange request, Principal principal) {
        service.changeOwnPassword(principal.getName(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /** Two requests created the same username at the same time. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    void duplicateUsername() {
    }
}
